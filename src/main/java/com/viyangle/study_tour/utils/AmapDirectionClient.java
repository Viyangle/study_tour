package com.viyangle.study_tour.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 高德 v5 路径规划客户端，供行程规划 Agent 计算相邻地点之间的交通段。
 *
 * <p>步行与驾车使用 {@code /v5/direction/walking}、{@code /v5/direction/driving}，
 * 公交使用 {@code /v5/direction/transit/integrated}。
 * 任何一次查询失败（未配置 key、参数非法、高德返回错误、网络异常）都返回 {@code null}，
 * 由调用方回退为直线估算并在方案中给出警示，绝不把未知值当成已知值。</p>
 *
 * <p>本类只统计“一次调用一个交通段”，调用次数上限由 Agent 的单轮预算控制；
 * 相同的（方式、起点、终点、城市）在短时间内直接复用缓存，对应文档中的“重复读使用本轮缓存”。</p>
 */
@Slf4j
@Component
public class AmapDirectionClient {

    /** 交通方式，与文档中的 walking / transit / driving 一一对应。 */
    public enum Mode {
        WALKING, TRANSIT, DRIVING
    }

    /** 一段交通的解析结果；无法核实的字段保持 null。 */
    @Data
    public static class LegResult {
        private Integer minutes;
        private Integer distanceMeters;
        private Integer walkingMeters;
        private Double cost;
        private List<List<Double>> polyline;
        private List<String> instructions;
        private String source;
        private String note;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_INSTRUCTIONS = 30;
    private static final int MAX_POLYLINE_POINTS = 2000;
    private static final int MAX_CACHE_ENTRIES = 2000;
    private static final String DEFAULT_SOURCE = "高德路径规划";
    private static final String DEFAULT_NOTE = "查询时参考结果，不保证未来交通情况";
    private static final String DEFAULT_WALKING_ENDPOINT =
            "https://restapi.amap.com/v5/direction/walking";
    private static final String DEFAULT_DRIVING_ENDPOINT =
            "https://restapi.amap.com/v5/direction/driving";
    private static final String DEFAULT_TRANSIT_ENDPOINT =
            "https://restapi.amap.com/v5/direction/transit/integrated";

    @Value("${app.amap.key:}")
    private String amapKey;

    @Value("${app.amap.walking-endpoint:https://restapi.amap.com/v5/direction/walking}")
    private String walkingEndpoint;

    @Value("${app.amap.driving-endpoint:https://restapi.amap.com/v5/direction/driving}")
    private String drivingEndpoint;

    @Value("${app.amap.transit-endpoint:https://restapi.amap.com/v5/direction/transit/integrated}")
    private String transitEndpoint;

    @Value("${app.amap.direction-timeout-seconds:10}")
    private int timeoutSeconds;

    @Value("${app.amap.direction-qps:8}")
    private int directionQps;

    @Value("${app.amap.direction-cache-ttl-minutes:10}")
    private long cacheTtlMinutes;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AtomicLong nextAllowedRequestTsMs = new AtomicLong(0L);
    private final ConcurrentMap<String, CachedLeg> cache = new ConcurrentHashMap<>();

    /**
     * 查询一段交通。返回 null 表示无法核实，调用方应回退为直线估算。
     *
     * @param mode                交通方式
     * @param originLocation      起点 "经度,纬度"
     * @param destinationLocation 终点 "经度,纬度"
     * @param citycode            城市编码，公交查询需要；其他方式可传 null
     */
    public LegResult route(Mode mode, String originLocation, String destinationLocation, String citycode) {
        if (mode == null || blank(amapKey)) {
            return null;
        }
        if (!validLocation(originLocation) || !validLocation(destinationLocation)) {
            return null;
        }

        String origin = originLocation.trim();
        String destination = destinationLocation.trim();
        String city = citycode == null ? "" : citycode.trim();
        String cacheKey = cacheKey(mode, origin, destination, city);
        LegResult cached = lookup(cacheKey);
        if (cached != null) {
            return cached;
        }

        LegResult result = request(mode, origin, destination, city);
        if (result != null) {
            store(cacheKey, result);
        }
        return result;
    }

    /** 缓存键：方式 + 起终点 + 城市；坐标保持高德返回的原始顺序，不交换方向。 */
    static String cacheKey(Mode mode, String origin, String destination, String citycode) {
        return "amap:direction:v1:" + mode.name().toLowerCase(Locale.ROOT)
                + ":" + origin + ":" + destination + ":" + (citycode == null ? "" : citycode);
    }

    private String buildUrl(Mode mode, String origin, String destination, String citycode) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("key", amapKey);
        query.put("origin", origin);
        query.put("destination", destination);
        switch (mode) {
            case WALKING -> {
                // 步行规划没有费用字段，无需 show_fields
            }
            case DRIVING -> query.put("show_fields", "cost");
            case TRANSIT -> {
                if (!blank(citycode)) {
                    query.put("city1", citycode);
                    query.put("city2", citycode);
                }
                query.put("show_fields", "cost");
            }
        }

        String endpoint = endpointOf(mode);

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

    /** 未配置端点时回退到高德 v5 官方地址，避免因缺少配置抛出空指针。 */
    private String endpointOf(Mode mode) {
        String configured = switch (mode) {
            case WALKING -> walkingEndpoint;
            case DRIVING -> drivingEndpoint;
            case TRANSIT -> transitEndpoint;
        };
        if (!blank(configured)) {
            return configured.trim();
        }
        return switch (mode) {
            case WALKING -> DEFAULT_WALKING_ENDPOINT;
            case DRIVING -> DEFAULT_DRIVING_ENDPOINT;
            case TRANSIT -> DEFAULT_TRANSIT_ENDPOINT;
        };
    }

    private LegResult request(Mode mode, String origin, String destination, String citycode) {
        String url = buildUrl(mode, origin, destination, citycode);
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
                log.warn("高德路径规划 HTTP 状态异常, mode={}, status={}", mode, response.statusCode());
                return null;
            }
            String body = new String(response.body(), StandardCharsets.UTF_8);
            return parse(mode, MAPPER.readTree(body));
        } catch (Exception e) {
            log.warn("高德路径规划调用失败, mode={}, reason={}", mode, e.getMessage());
            return null;
        }
    }

    /**
     * 解析高德 v5 路径规划响应。返回 null 表示响应不可用（非 status=1 或缺少路径）。
     * 独立成静态方法便于单元测试，不发起任何请求。
     */
    static LegResult parse(Mode mode, JsonNode root) {
        if (root == null || !"1".equals(text(root, "status"))) {
            return null;
        }
        JsonNode route = root.path("route");
        LegResult result = mode == Mode.TRANSIT
                ? parseTransit(route)
                : parsePath(mode, route.path("paths"));
        if (result == null) {
            return null;
        }
        result.setSource(DEFAULT_SOURCE);
        result.setNote(DEFAULT_NOTE);
        if (result.getPolyline() == null) {
            result.setPolyline(new ArrayList<>());
        }
        if (result.getInstructions() == null) {
            result.setInstructions(new ArrayList<>());
        }
        return result;
    }

    private static LegResult parsePath(Mode mode, JsonNode paths) {
        if (!paths.isArray() || paths.isEmpty()) {
            return null;
        }
        JsonNode path = paths.get(0);
        LegResult result = new LegResult();
        Integer distance = positiveInt(path.path("distance"));
        result.setDistanceMeters(distance);
        result.setMinutes(minutesOf(durationSeconds(path), distance));
        result.setWalkingMeters(mode == Mode.WALKING ? distance : null);
        // 驾车通行费不属于“每人行程费用估算”，按文档约定保持 null。
        result.setCost(null);

        List<List<Double>> polyline = new ArrayList<>();
        List<String> instructions = new ArrayList<>();
        JsonNode steps = path.path("steps");
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                appendInstruction(instructions, text(step, "instruction"), text(step, "road_name"));
                appendPolyline(polyline, text(step, "polyline"));
            }
        }
        result.setPolyline(polyline);
        result.setInstructions(instructions);
        return result;
    }

    private static LegResult parseTransit(JsonNode route) {
        JsonNode transits = route.path("transits");
        if (!transits.isArray() || transits.isEmpty()) {
            return null;
        }
        JsonNode transit = transits.get(0);
        LegResult result = new LegResult();
        Integer distance = positiveInt(transit.path("distance"));
        if (distance == null) {
            distance = positiveInt(route.path("distance"));
        }
        result.setDistanceMeters(distance);
        result.setMinutes(minutesOf(longOf(transit.path("duration")), distance));
        result.setWalkingMeters(positiveInt(transit.path("walking_distance")));
        result.setCost(positiveDouble(transit.path("cost")));

        List<List<Double>> polyline = new ArrayList<>();
        List<String> instructions = new ArrayList<>();
        JsonNode segments = transit.path("segments");
        if (segments.isArray()) {
            for (JsonNode segment : segments) {
                appendTransitWalking(segment.path("walking"), instructions, polyline);
                appendTransitBus(segment.path("bus"), instructions, polyline);
                appendTransitWalking(segment.path("railway"), instructions, polyline);
            }
        }
        if (instructions.isEmpty()) {
            appendInstruction(instructions, text(transit, "instruction"), null);
        }
        result.setPolyline(polyline);
        result.setInstructions(instructions);
        return result;
    }

    private static void appendTransitWalking(JsonNode walking, List<String> instructions,
                                             List<List<Double>> polyline) {
        if (walking == null || walking.isMissingNode()) {
            return;
        }
        JsonNode steps = walking.path("steps");
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                appendInstruction(instructions, text(step, "instruction"), null);
                appendPolyline(polyline, text(step, "polyline"));
            }
            return;
        }
        appendInstruction(instructions, text(walking, "instruction"), null);
        appendPolyline(polyline, text(walking, "polyline"));
    }

    private static void appendTransitBus(JsonNode bus, List<String> instructions,
                                         List<List<Double>> polyline) {
        if (bus == null || bus.isMissingNode()) {
            return;
        }
        JsonNode buslines = bus.path("buslines");
        if (buslines.isArray()) {
            for (JsonNode line : buslines) {
                String name = text(line, "name");
                if (!name.isBlank()) {
                    appendInstruction(instructions, "乘坐 " + name, text(line, "departure_stop"));
                }
                appendPolyline(polyline, text(line, "polyline"));
            }
            return;
        }
        appendInstruction(instructions, text(bus, "instruction"), null);
        appendPolyline(polyline, text(bus, "polyline"));
    }

    private static void appendInstruction(List<String> instructions, String instruction, String fallback) {
        if (instructions.size() >= MAX_INSTRUCTIONS) {
            return;
        }
        String value = blank(instruction) ? fallback : instruction;
        if (blank(value)) {
            return;
        }
        String trimmed = value.trim();
        if (!instructions.contains(trimmed)) {
            instructions.add(trimmed);
        }
    }

    private static void appendPolyline(List<List<Double>> polyline, String encoded) {
        if (blank(encoded) || polyline.size() >= MAX_POLYLINE_POINTS) {
            return;
        }
        for (String pair : encoded.split(";")) {
            if (polyline.size() >= MAX_POLYLINE_POINTS) {
                return;
            }
            String[] parts = pair.split(",");
            if (parts.length != 2) {
                continue;
            }
            try {
                List<Double> point = List.of(Double.parseDouble(parts[0].trim()),
                        Double.parseDouble(parts[1].trim()));
                // 相邻步骤的首尾点会重复，去掉连续重复点，几何形状保持不变
                if (polyline.isEmpty() || !polyline.get(polyline.size() - 1).equals(point)) {
                    polyline.add(point);
                }
            } catch (NumberFormatException ignored) {
                // 单点坐标异常时跳过该点，不影响整段交通的其余解析
            }
        }
    }

    /**
     * 高德 v5 的耗时单位为秒，位点不一致时依次尝试
     * {@code cost.duration}、{@code duration}、{@code cost}；都取不到时由调用方按距离估算。
     */
    private static Long durationSeconds(JsonNode path) {
        Long seconds = longOf(path.path("cost").path("duration"));
        if (seconds == null) {
            seconds = longOf(path.path("duration"));
        }
        if (seconds == null) {
            seconds = longOf(path.path("cost"));
        }
        return seconds == null ? null : Math.max(0, seconds);
    }

    private static Integer minutesOf(Long seconds, Integer distanceMeters) {
        if (seconds == null) {
            return null;
        }
        int minutes = (int) Math.ceil(seconds / 60.0);
        if (minutes == 0 && distanceMeters != null && distanceMeters > 0) {
            minutes = 1;
        }
        return Math.max(0, minutes);
    }

    private static boolean validLocation(String location) {
        if (blank(location)) {
            return false;
        }
        String[] parts = location.trim().split(",");
        if (parts.length != 2) {
            return false;
        }
        try {
            double lng = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());
            return !Double.isNaN(lng) && !Double.isNaN(lat);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Integer positiveInt(JsonNode node) {
        Long value = longOf(node);
        if (value == null || value <= 0) {
            return null;
        }
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    private static Double positiveDouble(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        try {
            double value = node.isNumber() ? node.asDouble() : Double.parseDouble(node.asText("").trim());
            return value > 0 ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Long longOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        String text = node.asText("").trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private LegResult lookup(String cacheKey) {
        CachedLeg cached = cache.get(cacheKey);
        if (cached == null) {
            return null;
        }
        if (cached.expiresAtMs < System.currentTimeMillis()) {
            cache.remove(cacheKey, cached);
            return null;
        }
        return cached.result;
    }

    private void store(String cacheKey, LegResult result) {
        if (cacheTtlMinutes <= 0) {
            return;
        }
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            long now = System.currentTimeMillis();
            cache.entrySet().removeIf(entry -> entry.getValue().expiresAtMs < now);
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(cacheKey, new CachedLeg(result, System.currentTimeMillis() + cacheTtlMinutes * 60_000L));
    }

    private void acquireRateLimitSlot() {
        if (directionQps <= 0) {
            return;
        }
        long intervalMs = Math.max(1L, Math.floorDiv(1000L, directionQps));
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

    private record CachedLeg(LegResult result, long expiresAtMs) {
    }

}
