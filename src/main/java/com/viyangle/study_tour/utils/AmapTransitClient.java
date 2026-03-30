package com.viyangle.study_tour.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.viyangle.study_tour.pojo.Attraction;
import lombok.Data;
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
import java.util.Map;

@Component
public class AmapTransitClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${app.amap.transit-endpoint:https://restapi.amap.com/v5/direction/transit/integrated}")
    private String endpoint;

    @Value("${app.amap.key:}")
    private String amapKey;

    @Value("${app.amap.transit-timeout-seconds:20}")
    private int timeoutSeconds;

    @Value("${app.amap.transit-cache-ttl-hours:24}")
    private long cacheTtlHours;

    @Value("${app.amap.transit-sleep-millis:80}")
    private long sleepMillis;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public List<TransitEdge> buildUndirectedMatrix(List<Attraction> attractions) {
        if (attractions == null || attractions.size() < 2) {
            return List.of();
        }
        List<TransitEdge> edges = new ArrayList<>();
        for (int i = 0; i < attractions.size(); i++) {
            for (int j = i + 1; j < attractions.size(); j++) {
                Attraction from = attractions.get(i);
                Attraction to = attractions.get(j);
                TransitEdge edge = getTransitEdge(from, to);
                if (edge != null) {
                    edges.add(edge);
                }
                if (sleepMillis > 0) {
                    try {
                        Thread.sleep(sleepMillis);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        return edges;
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

    private TransitEdge requestEdge(Attraction from, Attraction to) {
        String url = buildUrl(from, to);
        try {
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

    private String buildCacheKey(Attraction from, Attraction to) {
        return "amap:transit:v1:" + from.getPoiId() + ":" + to.getPoiId();
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

