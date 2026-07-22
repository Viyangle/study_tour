package com.viyangle.study_tour.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 批量给景点打研学标签的离线工具。
 *
 * 工作方式：
 * 1. 从 attractions 表读取所有景点
 * 2. 每批 10 个，发给 LLM，让它根据景点名称、类型、地址判断适合的研学标签
 * 3. 解析 LLM 返回的 JSON，写入 attraction_tag 表
 *
 * 运行方式：
 *   设置环境变量 OPENAI_API_KEY（dashscope API Key）
 *   mvn -DskipTests exec:java -Dexec.mainClass="com.viyangle.study_tour.utils.AttractionTagBatchLabeler"
 */
public class AttractionTagBatchLabeler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // dashscope OpenAI-compatible endpoint
    private static final String LLM_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String LLM_MODEL = "qwen-plus";

    private static final String DB_URL = System.getenv().getOrDefault("DB_URL", "jdbc:mysql://localhost:3306/study_tour?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
    private static final String DB_USER = System.getenv().getOrDefault("DB_USER", "root");
    private static final String DB_PASS = System.getenv().getOrDefault("DB_PASS", "");

    private static final List<String> TAG_DICTIONARY = List.of(
            "历史人文", "博物馆研学", "非遗体验", "科技探索", "自然生态",
            "地理地质", "航天航空", "农耕劳动", "艺术美育", "红色教育",
            "高校参访", "职业启蒙", "英语实践", "摄影记录", "亲子互动"
    );

    private static final int BATCH_SIZE = 10;
    private static final long SLEEP_BETWEEN_BATCHES_MS = 1000;

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY 环境变量未设置");
        }
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            processAll(conn, apiKey);
        }
    }

    /**
     * 核心处理逻辑，可被外部调用（传入 Connection 和 apiKey）。
     * 返回新增标签数，0 表示无需处理。
     */
    public static int processAll(Connection conn, String apiKey) throws Exception {
        List<AttractionRow> attractions = loadAttractions(conn);
        System.out.println("共加载 " + attractions.size() + " 个景点");

        Map<String, List<String>> existingTags = loadExistingTags(conn);
        System.out.println("已有标签的景点数: " + existingTags.size());

        Map<Long, String> tagIdNameMap = loadTagIdNameMap(conn);
        Map<String, Long> tagNameIdMap = new LinkedHashMap<>();
        tagIdNameMap.forEach((id, name) -> tagNameIdMap.put(name, id));

        int totalTagged = 0;
        int batchCount = 0;

        String insertSql = "INSERT IGNORE INTO attraction_tag(poi_id, tag_id, source, created_at) VALUES (?, ?, 'LLM', ?)";

        for (int i = 0; i < attractions.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, attractions.size());
            List<AttractionRow> batch = attractions.subList(i, end);

            // 跳过已有标签的景点
            List<AttractionRow> needLabel = new ArrayList<>();
            for (AttractionRow row : batch) {
                if (!existingTags.containsKey(row.poiId)) {
                    needLabel.add(row);
                }
            }

            if (needLabel.isEmpty()) {
                continue;
            }

            Map<String, List<String>> result = callLlmForBatch(apiKey, needLabel);
            batchCount++;

            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                for (AttractionRow row : needLabel) {
                    List<String> tags = result.get(row.poiId);
                    if (tags == null || tags.isEmpty()) {
                        continue;
                    }
                    for (String tagName : tags) {
                        Long tagId = tagNameIdMap.get(tagName);
                        if (tagId == null) {
                            System.out.println("  警告: LLM返回了未知标签 '" + tagName + "'，跳过");
                            continue;
                        }
                        ps.setString(1, row.poiId);
                        ps.setLong(2, tagId);
                        ps.setTimestamp(3, java.sql.Timestamp.valueOf(LocalDateTime.now()));
                        ps.addBatch();
                        totalTagged++;
                    }
                }
                ps.executeBatch();
            }

            System.out.printf("批次 %d: 处理 %d 个景点, 累计标签 %d 条%n",
                    batchCount, needLabel.size(), totalTagged);

            if (end < attractions.size()) {
                Thread.sleep(SLEEP_BETWEEN_BATCHES_MS);
            }
        }

        System.out.println("完成! 共处理 " + batchCount + " 批, 新增标签 " + totalTagged + " 条");
        return totalTagged;
    }

    /**
     * 统计还有多少景点没有打标签。
     */
    public static int countUntagged(Connection conn) throws Exception {
        String sql = "SELECT COUNT(*) FROM attractions a " +
                "WHERE NOT EXISTS (SELECT 1 FROM attraction_tag at WHERE at.poi_id = a.poi_id)";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * 调用 LLM 给一批景点打标签。
     * 返回 Map<poiId, List<tagName>>
     */
    private static Map<String, List<String>> callLlmForBatch(String apiKey, List<AttractionRow> batch) {
        String prompt = buildPrompt(batch);

        try {
            ObjectNode requestBody = MAPPER.createObjectNode();
            requestBody.put("model", LLM_MODEL);
            requestBody.put("temperature", 0.1);

            ArrayNode messages = requestBody.putArray("messages");
            ObjectNode sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", "你是一个研学旅游标签分类专家。根据景点信息，从标签字典中选择合适的标签。只输出JSON，不要解释。");

            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);

            String jsonBody = MAPPER.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(LLM_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                System.out.println("  LLM 请求失败: " + response.statusCode() + " " + response.body().substring(0, Math.min(200, response.body().length())));
                return Map.of();
            }

            JsonNode root = MAPPER.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            return parseLlmResponse(content, batch);

        } catch (Exception e) {
            System.out.println("  LLM 调用异常: " + e.getMessage());
            return Map.of();
        }
    }

    private static String buildPrompt(List<AttractionRow> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下景点选择合适的研学标签。\n\n");
        sb.append("标签字典（只能从这些里面选）：\n");
        sb.append(String.join("、", TAG_DICTIONARY)).append("\n\n");
        sb.append("景点列表：\n");
        for (AttractionRow row : batch) {
            sb.append("- poiId=").append(row.poiId);
            sb.append(", name=").append(nullToEmpty(row.name));
            sb.append(", type=").append(nullToEmpty(row.type));
            sb.append(", address=").append(nullToEmpty(row.address));
            sb.append(", cityname=").append(nullToEmpty(row.cityname));
            sb.append(", adname=").append(nullToEmpty(row.adname));
            sb.append("\n");
        }
        sb.append("\n请输出JSON格式，结构为：\n");
        sb.append("{\n");
        sb.append("  \"poiId1\": [\"标签1\", \"标签2\"],\n");
        sb.append("  \"poiId2\": [\"标签3\"]\n");
        sb.append("}\n");
        sb.append("每个景点1-3个标签，没有合适标签的景点可以返回空数组。");
        return sb.toString();
    }

    private static Map<String, List<String>> parseLlmResponse(String content, List<AttractionRow> batch) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try {
            // 去掉可能的 markdown 代码块标记
            String json = content.replaceAll("(?s)^```json\\s*", "").replaceAll("(?s)\\s*```$", "").trim();
            JsonNode root = MAPPER.readTree(json);

            for (AttractionRow row : batch) {
                JsonNode tags = root.get(row.poiId);
                if (tags != null && tags.isArray()) {
                    List<String> tagList = new ArrayList<>();
                    for (JsonNode tag : tags) {
                        String tagName = tag.asText("").trim();
                        if (!tagName.isEmpty() && TAG_DICTIONARY.contains(tagName)) {
                            tagList.add(tagName);
                        }
                    }
                    if (!tagList.isEmpty()) {
                        result.put(row.poiId, tagList);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("  解析LLM响应失败: " + e.getMessage());
            System.out.println("  原始内容: " + content.substring(0, Math.min(300, content.length())));
        }
        return result;
    }

    private static List<AttractionRow> loadAttractions(Connection conn) throws Exception {
        List<AttractionRow> list = new ArrayList<>();
        String sql = "SELECT poi_id, name, type, address, cityname, adname FROM attractions ORDER BY poi_id";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                AttractionRow row = new AttractionRow();
                row.poiId = rs.getString("poi_id");
                row.name = rs.getString("name");
                row.type = rs.getString("type");
                row.address = rs.getString("address");
                row.cityname = rs.getString("cityname");
                row.adname = rs.getString("adname");
                list.add(row);
            }
        }
        return list;
    }

    private static Map<String, List<String>> loadExistingTags(Connection conn) throws Exception {
        Map<String, List<String>> map = new LinkedHashMap<>();
        String sql = "SELECT poi_id, tag_id FROM attraction_tag";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String poiId = rs.getString("poi_id");
                map.computeIfAbsent(poiId, k -> new ArrayList<>()).add(String.valueOf(rs.getLong("tag_id")));
            }
        }
        return map;
    }

    private static Map<Long, String> loadTagIdNameMap(Connection conn) throws Exception {
        Map<Long, String> map = new LinkedHashMap<>();
        String sql = "SELECT id, name FROM tags";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getLong("id"), rs.getString("name"));
            }
        }
        return map;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static class AttractionRow {
        String poiId;
        String name;
        String type;
        String address;
        String cityname;
        String adname;
    }
}
