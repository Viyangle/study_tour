package com.viyangle.study_tour.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 相邻景点关系计算工具。
 *
 * 工作方式：
 * 1. 从 attractions 表读取所有景点
 * 2. 按城市（adcode 前4位）分组
 * 3. 同城市内的景点两两查高德公交 API
 * 4. 公交通勤时间 <= 30 分钟的，写入 attraction_adjacency 表（双向）
 *
 * 运行方式：
 *   设置环境变量 AMAP_KEY
 *   mvn -DskipTests exec:java -Dexec.mainClass="com.viyangle.study_tour.utils.AttractionAdjacencyCalculator"
 */
public class AttractionAdjacencyCalculator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String DRIVING_ENDPOINT = "https://restapi.amap.com/v3/direction/driving";
    private static final int DISTANCE_THRESHOLD_METERS = 10000; // 10km 内视为相邻
    private static final int PARALLELISM = 32;
    private static final long QPS_INTERVAL_MS = 400; // 低于400会报status=0

    private static final String DB_URL = System.getenv().getOrDefault("DB_URL", "jdbc:mysql://localhost:3306/study_tour?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
    private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "root");
    private static final String DB_PASS = System.getenv().getOrDefault("DB_PASS", "");

    private static final AtomicLong NEXT_ALLOWED_TS = new AtomicLong(0L);
    private static int debugPrinted = 0; // 用于打印前几条 API 响应

    public static void main(String[] args) throws Exception {
        String amapKey = System.getenv("AMAP_KEY");
        if (amapKey == null || amapKey.isBlank()) {
            throw new IllegalStateException("AMAP_KEY 环境变量未设置");
        }
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            processAll(conn, amapKey);
        }
    }

    /**
     * 核心处理逻辑，可被外部调用（传入 Connection 和 amapKey）。
     * 返回新增相邻关系数，0 表示无需处理。
     */
    public static int processAll(Connection conn, String amapKey) throws Exception {
        List<AttractionInfo> attractions = loadAttractions(conn);
        System.out.println("共加载 " + attractions.size() + " 个景点");

        // 按城市分组 (adcode 前4位)
        Map<String, List<AttractionInfo>> cityGroups = new LinkedHashMap<>();
        for (AttractionInfo info : attractions) {
            if (info.adcode == null || info.adcode.length() < 4) continue;
            String cityKey = info.adcode.substring(0, 4);
            cityGroups.computeIfAbsent(cityKey, k -> new ArrayList<>()).add(info);
        }

        // 生成所有需要查询的 pair
        List<Pair> allPairs = new ArrayList<>();
        for (List<AttractionInfo> group : cityGroups.values()) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    AttractionInfo a = group.get(i);
                    AttractionInfo b = group.get(j);
                    if (a.location == null || b.location == null) continue;
                    allPairs.add(new Pair(a, b));
                }
            }
        }
        System.out.println("共 " + cityGroups.size() + " 个城市, " + allPairs.size() + " 个景点对需要查询");

        // 并行查询
        int workerCount = Math.max(1, PARALLELISM);
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        List<CompletableFuture<AdjacencyResult>> futures = new ArrayList<>();
        for (Pair pair : allPairs) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> queryTransit(amapKey, pair), executor));
        }

        // 收集结果
        List<AdjacencyResult> results = new ArrayList<>();
        for (CompletableFuture<AdjacencyResult> f : futures) {
            try {
                AdjacencyResult r = f.get();
                if (r != null) {
                    results.add(r);
                }
            } catch (Exception ignored) {
            }
        }
        shutdownExecutor(executor);

        System.out.println("有效结果: " + results.size());

        // 统计距离分布用于调试
        int inRange = 0;
        for (AdjacencyResult r : results) {
            if (r.distanceM >= 0 && r.distanceM <= DISTANCE_THRESHOLD_METERS) {
                inRange++;
            }
        }
        System.out.println("距离<=" + DISTANCE_THRESHOLD_METERS + "m的景点对: " + inRange);

        // 过滤并写入数据库
        int saved = 0;
        // 先清空旧数据
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM attraction_adjacency")) {
            del.executeUpdate();
        }

        String sql = "INSERT INTO attraction_adjacency(from_poi_id, to_poi_id, transit_minutes, distance_m, created_at) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (AdjacencyResult r : results) {
                // 只根据距离判断：10km内视为相邻
                if (r.distanceM < 0 || r.distanceM > DISTANCE_THRESHOLD_METERS) {
                    continue;
                }
                // 双向写入
                ps.setString(1, r.fromPoiId);
                ps.setString(2, r.toPoiId);
                ps.setInt(3, -1);
                ps.setInt(4, r.distanceM);
                ps.setTimestamp(5, java.sql.Timestamp.valueOf(LocalDateTime.now()));
                ps.addBatch();

                ps.setString(1, r.toPoiId);
                ps.setString(2, r.fromPoiId);
                ps.setInt(3, -1);
                ps.setInt(4, r.distanceM);
                ps.setTimestamp(5, java.sql.Timestamp.valueOf(LocalDateTime.now()));
                ps.addBatch();

                saved += 2;
            }
            ps.executeBatch();
        }

        System.out.println("完成! 保存了 " + saved + " 条相邻关系");
        return saved;
    }

    /**
     * 统计还有多少景点没有相邻关系。
     */
    public static int countWithoutAdjacency(Connection conn) throws Exception {
        String sql = "SELECT COUNT(*) FROM attractions a " +
                "WHERE a.location IS NOT NULL AND a.location != '' " +
                "AND NOT EXISTS (SELECT 1 FROM attraction_adjacency adj WHERE adj.from_poi_id = a.poi_id)";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static AdjacencyResult queryTransit(String amapKey, Pair pair) {
        try {
            acquireRateLimit();

            String url = buildDrivingUrl(amapKey, pair.from, pair.to);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                System.out.println("DEBUG: API 错误 - " + pair.from.name + "→" + pair.to.name + ", 状态码: " + resp.statusCode());
                return null;
            }

            JsonNode root = MAPPER.readTree(resp.body());
            String status = root.path("status").asText("");
            if (!"1".equals(status)) {
                String info = root.path("info").asText("unknown");
                System.out.println("DEBUG: API 返回非成功状态 - " + pair.from.name + "→" + pair.to.name + ", status=" + status + ", info=" + info);
                return null;
            }

            // 打印前3条成功的 API 响应用于调试
            if (debugPrinted < 3) {
                System.out.println("=== DEBUG: API 原始响应 ===");
                System.out.println("URL: " + url);
                System.out.println("Response: " + resp.body().substring(0, Math.min(2000, resp.body().length())));
                System.out.println("=========================");
                debugPrinted++;
            }

            JsonNode paths = root.path("route").path("paths");
            if (!paths.isArray() || paths.isEmpty()) return null;
            JsonNode path0 = paths.get(0);
            int distanceM = intValue(path0.path("distance"));

            return new AdjacencyResult(pair.from.poiId, pair.to.poiId, distanceM);
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildDrivingUrl(String amapKey, AttractionInfo from, AttractionInfo to) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("key", amapKey);
        query.put("origin", from.location);
        query.put("destination", to.location);
        query.put("originpoi", from.poiId);
        query.put("destinationpoi", to.poiId);

        StringBuilder sb = new StringBuilder(DRIVING_ENDPOINT).append("?");
        boolean first = true;
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (!first) sb.append("&");
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append("=");
            sb.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return sb.toString();
    }

    private static void acquireRateLimit() {
        long intervalMs = QPS_INTERVAL_MS;
        while (true) {
            long now = System.currentTimeMillis();
            long current = NEXT_ALLOWED_TS.get();
            long next = Math.max(now, current) + intervalMs;
            if (NEXT_ALLOWED_TS.compareAndSet(current, next)) {
                long waitMs = next - intervalMs - now;
                if (waitMs > 0) {
                    try { Thread.sleep(waitMs); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                return;
            }
        }
    }

    private static int intValue(JsonNode node) {
        if (node == null || node.isNull()) return -1;
        if (node.isNumber()) return node.asInt();
        String text = node.asText("");
        if (text.isBlank()) return -1;
        try { return Integer.parseInt(text.trim()); } catch (Exception e) { return -1; }
    }

    private static void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            executor.shutdownNow();
        }
    }

    private static List<AttractionInfo> loadAttractions(Connection conn) throws Exception {
        List<AttractionInfo> list = new ArrayList<>();
        String sql = "SELECT poi_id, name, location, citycode, adcode FROM attractions WHERE location IS NOT NULL AND location != '' ORDER BY adcode, poi_id";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                AttractionInfo info = new AttractionInfo();
                info.poiId = rs.getString("poi_id");
                info.name = rs.getString("name");
                info.location = rs.getString("location");
                info.citycode = rs.getString("citycode");
                info.adcode = rs.getString("adcode");
                list.add(info);
            }
        }
        return list;
    }

    private static class AttractionInfo {
        String poiId, name, location, citycode, adcode;
    }

    private record Pair(AttractionInfo from, AttractionInfo to) {}

    private record AdjacencyResult(String fromPoiId, String toPoiId, int distanceM) {}
}
