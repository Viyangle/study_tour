package com.viyangle.study_tour.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
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
import java.util.List;

/**
 * 语义边计算工具（知识图谱中"景点→景点"的主题相似边）。
 *
 * 工作方式：
 * 1. 从 attractions 表读取所有景点
 * 2. 找出尚未计算 THEMATIC 边的景点对
 * 3. 调用阿里云 Qwen LLM 判断两个景点是否主题相关
 * 4. 将结果写入 attraction_adjacency 表（relation_type = 'THEMATIC'）
 *
 * 运行方式：
 *   设置环境变量 SEMANTIC_EDGE_API_KEY
 *   mvn -DskipTests exec:java -Dexec.mainClass="com.viyangle.study_tour.utils.SemanticEdgeCalculator"
 */
public class SemanticEdgeCalculator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String DB_URL = System.getenv().getOrDefault("DB_URL",
            "jdbc:mysql://localhost:3306/study_tour?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
    private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "root");
    private static final String DB_PASS = System.getenv().getOrDefault("DB_PASS", "");

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_MODEL = "qwen-plus";
    private static final double SIMILARITY_THRESHOLD = 0.6;

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("SEMANTIC_EDGE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("SEMANTIC_EDGE_API_KEY 环境变量未设置");
        }
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            processIncremental(conn, apiKey, DEFAULT_BASE_URL, DEFAULT_MODEL, 10, 500);
        }
    }

    /**
     * 统计有多少个景点还没有 THEMATIC 边（自引用占位也算已处理）。
     */
    public static int countWithoutSemanticEdges(Connection conn) throws Exception {
        String sql = """
                SELECT COUNT(*) FROM attractions a
                WHERE NOT EXISTS (
                    SELECT 1 FROM attraction_adjacency adj
                    WHERE adj.relation_type = 'THEMATIC'
                      AND (adj.from_poi_id = a.poi_id OR adj.to_poi_id = a.poi_id)
                )
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * 增量处理：只计算还没有 THEMATIC 边的景点。
     * 返回新增语义边数。
     */
    public static int processIncremental(Connection conn, String apiKey,
                                          String baseUrl, String modelName,
                                          int batchSize, long sleepMillis) throws Exception {
        List<AttractionInfo> allAttractions = loadAttractions(conn);
        if (allAttractions.size() < 2) {
            System.out.println("景点数量不足，跳过语义边计算");
            return 0;
        }

        // 找出还没处理过的景点（没有任何 THEMATIC 边）
        List<AttractionInfo> unprocessed = loadAttractionsWithoutSemanticEdge(conn);
        if (unprocessed.isEmpty()) {
            System.out.println("所有景点已有语义边，无需计算");
            return 0;
        }
        System.out.println("语义边增量计算: " + unprocessed.size() + " 个景点缺少 THEMATIC 边");

        int addedCount = 0;

        // 对每个未处理景点，与所有其他景点配对发给 LLM
        for (AttractionInfo unproc : unprocessed) {
            List<Pair> pairs = new ArrayList<>();
            for (AttractionInfo other : allAttractions) {
                if (other.poiId.equals(unproc.poiId)) continue;
                pairs.add(new Pair(unproc, other));
            }

            // 分批调用 LLM
            for (int i = 0; i < pairs.size(); i += batchSize) {
                List<Pair> batch = pairs.subList(i, Math.min(i + batchSize, pairs.size()));
                addedCount += callLLMAndSave(conn, apiKey, baseUrl, modelName, batch);
                Thread.sleep(sleepMillis);
            }

            // 如果该景点没有任何 THEMATIC 边，插入自引用占位记录
            if (!hasThematicEdge(conn, unproc.poiId)) {
                insertSelfPlaceholder(conn, unproc.poiId);
            }
        }

        System.out.println("语义边增量计算完成，新增 " + addedCount + " 条 THEMATIC 边");
        return addedCount;
    }

    private static boolean hasThematicEdge(Connection conn, String poiId) throws Exception {
        String sql = """
                SELECT COUNT(*) FROM attraction_adjacency
                WHERE relation_type = 'THEMATIC'
                  AND from_poi_id = ? AND to_poi_id != from_poi_id
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, poiId);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private static void insertSelfPlaceholder(Connection conn, String poiId) throws Exception {
        String sql = """
                INSERT IGNORE INTO attraction_adjacency
                    (from_poi_id, to_poi_id, transit_minutes, distance_m, relation_type, similarity_score, created_at)
                VALUES (?, ?, NULL, -1, 'THEMATIC', 0.0, NOW())
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, poiId);
            ps.setString(2, poiId);
            ps.executeUpdate();
        }
    }

    /**
     * 调用 LLM 判断一批景点对的语义相关性，并保存结果。
     */
    private static int callLLMAndSave(Connection conn, String apiKey,
                                       String baseUrl, String modelName,
                                       List<Pair> batch) throws Exception {
        // 构建 prompt
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个旅游专家。请判断以下景点对是否在主题上相关（如：都是历史古迹、都是自然景观、都是博物馆等）。\n");
        sb.append("对每一对给出0到1之间的相似度分数。只返回JSON数组，格式：[{\"i\":0,\"s\":0.85},...]\n\n");
        sb.append("景点对列表：\n");
        for (int i = 0; i < batch.size(); i++) {
            Pair p = batch.get(i);
            sb.append(String.format("[%d] A: %s(%s)  B: %s(%s)\n",
                    i, p.a.name, p.a.type, p.b.name, p.b.type));
        }

        String response = callQwen(apiKey, baseUrl, modelName, sb.toString());
        return parseAndSave(conn, response, batch);
    }

    private static String callQwen(String apiKey, String baseUrl, String model, String prompt) throws Exception {
        String url = baseUrl + "/chat/completions";
        String body = MAPPER.writeValueAsString(java.util.Map.of(
                "model", model,
                "messages", java.util.List.of(
                        java.util.Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.3,
                "response_format", java.util.Map.of("type", "json_object")
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.err.println("LLM API 错误: " + response.statusCode() + " - " + response.body());
            return "[]";
        }

        JsonNode root = MAPPER.readTree(response.body());
        return root.path("choices").path(0).path("message").path("content").asText("[]");
    }

    private static int parseAndSave(Connection conn, String llmResponse, List<Pair> batch) throws Exception {
        int count = 0;
        try {
            JsonNode arr = MAPPER.readTree(llmResponse);
            if (!arr.isArray()) return 0;

            for (JsonNode node : arr) {
                int idx = node.path("i").asInt(-1);
                double score = node.path("s").asDouble(0.0);
                if (idx < 0 || idx >= batch.size()) continue;
                if (score < SIMILARITY_THRESHOLD) continue;

                Pair p = batch.get(idx);
                insertThematicEdge(conn, p.a.poiId, p.b.poiId, score);
                insertThematicEdge(conn, p.b.poiId, p.a.poiId, score);
                count += 2;
            }
        } catch (Exception e) {
            System.err.println("解析 LLM 响应失败: " + e.getMessage());
            System.err.println("响应内容: " + llmResponse);
        }
        return count;
    }

    private static void insertThematicEdge(Connection conn, String fromPoiId, String toPoiId, double score) throws Exception {
        String sql = """
                INSERT INTO attraction_adjacency
                    (from_poi_id, to_poi_id, transit_minutes, distance_m, relation_type, similarity_score, created_at)
                VALUES (?, ?, NULL, NULL, 'THEMATIC', ?, NOW())
                ON DUPLICATE KEY UPDATE similarity_score = VALUES(similarity_score)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fromPoiId);
            ps.setString(2, toPoiId);
            ps.setDouble(3, score);
            ps.executeUpdate();
        }
    }

    // ========== 数据加载 ==========

    private static List<AttractionInfo> loadAttractions(Connection conn) throws Exception {
        String sql = "SELECT poi_id, name, type, adcode FROM attractions WHERE status = 'ACTIVE'";
        List<AttractionInfo> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new AttractionInfo(
                        rs.getString("poi_id"),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getString("adcode")
                ));
            }
        }
        return list;
    }

    private static List<AttractionInfo> loadAttractionsWithoutSemanticEdge(Connection conn) throws Exception {
        String sql = """
                SELECT a.poi_id, a.name, a.type, a.adcode
                FROM attractions a
                WHERE a.status = 'ACTIVE'
                  AND NOT EXISTS (
                      SELECT 1 FROM attraction_adjacency adj
                      WHERE adj.relation_type = 'THEMATIC'
                        AND (adj.from_poi_id = a.poi_id OR adj.to_poi_id = a.poi_id)
                  )
                """;
        List<AttractionInfo> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new AttractionInfo(
                        rs.getString("poi_id"),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getString("adcode")
                ));
            }
        }
        return list;
    }

    // ========== 内部类 ==========

    private record AttractionInfo(String poiId, String name, String type, String adcode) {}

    private record Pair(AttractionInfo a, AttractionInfo b) {}
}
