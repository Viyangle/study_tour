package com.viyangle.study_tour.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.viyangle.study_tour.pojo.Attraction;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class AmapTransitClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${app.amap.transit-endpoint:https://restapi.amap.com/v5/direction/transit/integrated}")
    private String endpoint;

    @Value("${app.amap.detail-endpoint:https://restapi.amap.com/v5/place/detail}")
    private String detailEndpoint;

    @Value("${app.amap.key:}")
    private String amapKey;

    @Value("${app.amap.transit-timeout-seconds:20}")
    private int timeoutSeconds;

    @Value("${app.amap.transit-cache-ttl-hours:24}")
    private long cacheTtlHours;

    @Value("${app.amap.transit-sleep-millis:80}")
    private long sleepMillis;

    @Value("${app.amap.transit-parallelism:6}")
    private int parallelism;

    @Value("${app.amap.transit-qps:8}")
    private int transitQps;

    @Value("${app.amap.transit-max-retries:1}")
    private int maxRetries;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AtomicLong nextAllowedRequestTsMs = new AtomicLong(0L);
    private final AtomicLong matrixThreadSequence = new AtomicLong(0L);
    private final ConcurrentMap<String, CompletableFuture<TransitEdge>> inFlightEdges = new ConcurrentHashMap<>();
    private volatile ExecutorService matrixExecutor;

    @PostConstruct
    void initializeMatrixExecutor() {
        ensureMatrixExecutor();
    }

    @PreDestroy
    void destroyMatrixExecutor() {
        shutdownExecutor(matrixExecutor);
    }

    public List<TransitEdge> buildUndirectedMatrix(List<Attraction> attractions) {
        if (attractions == null || attractions.size() < 2) {
            return List.of();
        }

        long startedAt = System.nanoTime();
        Map<String, Pair> pairsByCacheKey = new LinkedHashMap<>();
        for (int i = 0; i < attractions.size(); i++) {
            for (int j = i + 1; j < attractions.size(); j++) {
                Pair pair = new Pair(attractions.get(i), attractions.get(j));
                String cacheKey = buildCacheKey(pair.from(), pair.to());
                if (cacheKey != null) {
                    pairsByCacheKey.putIfAbsent(cacheKey, pair);
                }
            }
        }

        Map<String, TransitEdge> cachedEdges = readCachedEdges(pairsByCacheKey.keySet().stream().toList());
        List<CompletableFuture<TransitEdge>> futures = new ArrayList<>(pairsByCacheKey.size());
        for (Map.Entry<String, Pair> entry : pairsByCacheKey.entrySet()) {
            TransitEdge cachedEdge = cachedEdges.get(entry.getKey());
            if (cachedEdge != null) {
                futures.add(CompletableFuture.completedFuture(cachedEdge));
            } else {
                futures.add(getTransitEdgeAsync(entry.getKey(), entry.getValue()));
            }
        }

        List<TransitEdge> edges = new ArrayList<>(pairsByCacheKey.size());
        for (CompletableFuture<TransitEdge> future : futures) {
            try {
                TransitEdge edge = future.get();
                if (edge != null) {
                    edges.add(edge);
                }
            } catch (Exception ignored) {
                // Keep matrix generation best-effort; missing edges are tolerated by downstream planner.
            }
        }
        log.info("AMap transit matrix timing, candidates={}, pairs={}, cacheHits={}, cacheMisses={}, "
                        + "resultEdges={}, costMs={}",
                attractions.size(), pairsByCacheKey.size(), cachedEdges.size(),
                pairsByCacheKey.size() - cachedEdges.size(), edges.size(), elapsedMillis(startedAt));
        return edges;
    }

    private Map<String, TransitEdge> readCachedEdges(List<String> cacheKeys) {
        if (cacheKeys == null || cacheKeys.isEmpty()) {
            return Map.of();
        }
        try {
            List<String> cachedValues = redisTemplate.opsForValue().multiGet(cacheKeys);
            if (cachedValues == null || cachedValues.isEmpty()) {
                return Map.of();
            }
            Map<String, TransitEdge> result = new LinkedHashMap<>();
            int count = Math.min(cacheKeys.size(), cachedValues.size());
            for (int i = 0; i < count; i++) {
                String cached = cachedValues.get(i);
                if (cached == null || cached.isBlank()) {
                    continue;
                }
                try {
                    result.put(cacheKeys.get(i), MAPPER.readValue(cached, TransitEdge.class));
                } catch (Exception ignored) {
                    // A malformed cache entry is treated as a miss and refreshed below.
                }
            }
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private CompletableFuture<TransitEdge> getTransitEdgeAsync(String cacheKey, Pair pair) {
        CompletableFuture<TransitEdge> future = inFlightEdges.computeIfAbsent(cacheKey,
                key -> CompletableFuture.supplyAsync(
                    () -> getTransitEdgeWithRetry(pair.from(), pair.to()),
                    ensureMatrixExecutor()
                ));
        future.whenComplete((result, error) -> inFlightEdges.remove(cacheKey, future));
        return future;
    }

    public TransitEdge getTransitEdge(Attraction from, Attraction to) {
        if (from == null || to == null) {
            return null;
        }
        if (blank(from.getPoiId()) || blank(to.getPoiId()) || blank(from.getLocation()) || blank(to.getLocation())) {
            return null;
        }
        if (blank(amapKey)) {
            throw new IllegalStateException("AMap key is empty. Set app.amap.key or AMAP_KEY.");
        }

        String cacheKey = buildCacheKey(from, to);
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                return MAPPER.readValue(cached, TransitEdge.class);
            }
        } catch (Exception ignored) {
            // ignore cache read issues
        }

        TransitEdge edge = requestEdge(from, to);
        if (edge == null) {
            return null;
        }

        try {
            redisTemplate.opsForValue().set(cacheKey, MAPPER.writeValueAsString(edge), Duration.ofHours(cacheTtlHours));
        } catch (Exception ignored) {
            // ignore cache write issues
        }
        return edge;
    }

    /**
     * 通过高德 place/detail 接口按 poiId 补全景点信息。
     * 用于前端只提交了高德 poiId（未带名称/坐标等元数据）的场景。
     * 查询失败时返回 null，由调用方决定是否继续。
     */
    public Attraction fetchAttraction(String poiId) {
        if (blank(poiId) || blank(amapKey)) {
            return null;
        }
        String url = buildDetailUrl(poiId);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return null;
            }
            String body = new String(response.body(), StandardCharsets.UTF_8);
            JsonNode root = MAPPER.readTree(body);
            if (!"1".equals(text(root, "status"))) {
                return null;
            }
            JsonNode pois = root.path("pois");
            if (!pois.isArray() || pois.isEmpty()) {
                return null;
            }
            return toAttraction(pois.get(0));
        } catch (Exception e) {
            return null;
        }
    }

    private TransitEdge getTransitEdgeWithRetry(Attraction from, Attraction to) {
        int retries = Math.max(0, maxRetries);
        for (int attempt = 0; attempt <= retries; attempt++) {
            TransitEdge edge = getTransitEdge(from, to);
            if (edge != null) {
                return edge;
            }
            if (attempt < retries && sleepMillis > 0) {
                try {
                    Thread.sleep(Math.min(300L, sleepMillis));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private TransitEdge requestEdge(Attraction from, Attraction to) {
        String url = buildUrl(from, to);
        try {
            acquireRateLimitSlot();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return null;
            }
            String body = new String(response.body(), StandardCharsets.UTF_8);
            JsonNode root = MAPPER.readTree(body);
            TransitEdge edge = new TransitEdge();
            edge.setFromPoiId(from.getPoiId());
            edge.setToPoiId(to.getPoiId());
            edge.setFromName(from.getName());
            edge.setToName(to.getName());
            edge.setStatus(text(root, "status"));
            edge.setInfo(text(root, "info"));
            edge.setInfocode(text(root, "infocode"));

            JsonNode route = root.path("route");
            edge.setRouteDistanceM(intValue(route.path("distance")));

            JsonNode transits = route.path("transits");
            edge.setTransitCount(transits.isArray() ? transits.size() : 0);
            if (transits.isArray() && transits.size() > 0) {
                JsonNode best = transits.get(0);
                edge.setBestTransitDistanceM(intValue(best.path("distance")));
                edge.setBestWalkingDistanceM(intValue(best.path("walking_distance")));
                edge.setBestNightFlag(best.path("nightflag").asText(""));
                edge.setBestLines(extractLines(best.path("segments")));
            } else {
                edge.setBestTransitDistanceM(-1);
                edge.setBestWalkingDistanceM(-1);
                edge.setBestNightFlag("");
                edge.setBestLines(List.of());
            }
            return edge;
        } catch (Exception e) {
            return null;
        }
    }

    private String buildUrl(Attraction from, Attraction to) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("key", amapKey);
        query.put("origin", from.getLocation());
        query.put("destination", to.getLocation());
        query.put("originpoi", from.getPoiId());
        query.put("destinationpoi", to.getPoiId());
        query.put("city1", from.getCitycode());
        query.put("city2", to.getCitycode());

        StringBuilder sb = new StringBuilder(endpoint).append("?");
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (!first) {
                sb.append("&");
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append("=");
            sb.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    private String buildDetailUrl(String poiId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("key", amapKey);
        query.put("id", poiId);
        query.put("show_fields", "business");

        StringBuilder sb = new StringBuilder(detailEndpoint).append("?");
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (!first) {
                sb.append("&");
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append("=");
            sb.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    private Attraction toAttraction(JsonNode poi) {
        Attraction attraction = new Attraction();
        attraction.setPoiId(firstText(poi, "id", "poi_id"));
        attraction.setParentPoiId(firstText(poi, "parent", "parent_poi_id"));
        attraction.setName(text(poi, "name"));
        attraction.setAddress(text(poi, "address"));
        attraction.setLocation(text(poi, "location"));
        attraction.setPcode(text(poi, "pcode"));
        attraction.setPname(text(poi, "pname"));
        attraction.setCitycode(text(poi, "citycode"));
        attraction.setCityname(text(poi, "cityname"));
        attraction.setAdcode(text(poi, "adcode"));
        attraction.setAdname(text(poi, "adname"));
        attraction.setType(text(poi, "type"));
        attraction.setTypecode(text(poi, "typecode"));
        attraction.setDistance(text(poi, "distance"));
        JsonNode business = poi.path("business");
        attraction.setOpentimeToday(text(business, "opentime_today"));
        attraction.setOpentimeWeek(text(business, "opentime_week"));
        attraction.setTel(text(business, "tel"));
        attraction.setStatus("ACTIVE");
        return attraction;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!blank(value)) {
                return value;
            }
        }
        return "";
    }

    private String buildCacheKey(Attraction from, Attraction to) {
        if (from == null || to == null || blank(from.getPoiId()) || blank(to.getPoiId())) {
            return null;
        }
        String fromPoiId = from.getPoiId().trim().toUpperCase(Locale.ROOT);
        String toPoiId = to.getPoiId().trim().toUpperCase(Locale.ROOT);
        String first = fromPoiId.compareTo(toPoiId) <= 0 ? fromPoiId : toPoiId;
        String second = fromPoiId.compareTo(toPoiId) <= 0 ? toPoiId : fromPoiId;
        return "amap:transit:v1:" + first + ":" + second;
    }

    private static List<String> extractLines(JsonNode segments) {
        List<String> lines = new ArrayList<>();
        if (!segments.isArray()) {
            return lines;
        }
        for (JsonNode segment : segments) {
            ArrayNode buslines = segment.path("bus").path("buslines").isArray() ? (ArrayNode) segment.path("bus").path("buslines") : null;
            if (buslines == null) {
                continue;
            }
            for (JsonNode line : buslines) {
                String name = line.path("name").asText("").trim();
                if (!name.isEmpty()) {
                    lines.add(name);
                }
            }
        }
        return lines;
    }

    private static int intValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return -1;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        String text = node.asText("");
        if (text.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? "" : n.asText("");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private void acquireRateLimitSlot() {
        if (transitQps <= 0) {
            return;
        }
        long intervalMs = Math.max(1L, Math.floorDiv(1000L, transitQps));
        while (true) {
            long now = System.currentTimeMillis();
            long current = nextAllowedRequestTsMs.get();
            long start = Math.max(now, current);
            long next = start + intervalMs;
            if (nextAllowedRequestTsMs.compareAndSet(current, next)) {
                long waitMs = start - now;
                if (waitMs > 0) {
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                return;
            }
        }
    }

    private ExecutorService ensureMatrixExecutor() {
        ExecutorService current = matrixExecutor;
        if (current != null && !current.isShutdown()) {
            return current;
        }
        synchronized (this) {
            current = matrixExecutor;
            if (current == null || current.isShutdown()) {
                int workerCount = Math.max(1, parallelism);
                matrixExecutor = Executors.newFixedThreadPool(workerCount, runnable -> {
                    Thread thread = new Thread(runnable,
                            "amap-transit-" + matrixThreadSequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return matrixExecutor;
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private void shutdownExecutor(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record Pair(Attraction from, Attraction to) {
    }

    @Data
    public static class TransitEdge {
        private String fromPoiId;
        private String fromName;
        private String toPoiId;
        private String toName;
        private String status;
        private String info;
        private String infocode;
        private int routeDistanceM;
        private int transitCount;
        private int bestTransitDistanceM;
        private int bestWalkingDistanceM;
        private String bestNightFlag;
        private List<String> bestLines;
    }
}

