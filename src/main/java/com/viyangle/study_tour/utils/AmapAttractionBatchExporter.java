package com.viyangle.study_tour.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批量抓取高德 polygon 景点数据，并输出：
 * 1) others/attractions_page_XXX.json（每页原始返回）
 * 2) content/attractions_merged.json（去重后的标准化数据）
 * 3) others/attractions_import.sql（可直接导入 attractions 表）
 *
 * 运行示例：
 * mvn -DskipTests exec:java -Dexec.mainClass="com.viyangle.study_tour.utils.AmapAttractionBatchExporter"
 */
public class AmapAttractionBatchExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final List<String> DB_COLUMNS = List.of(
            "poi_id", "parent_poi_id", "name", "address", "location",
            "pcode", "pname", "citycode", "cityname", "adcode", "adname",
            "type", "typecode", "distance"
    );

    public static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        String batchId = LocalDateTime.now().format(TS);
        Path mergedOutputDir = Paths.get(config.mergedOutputDir);
        Path othersOutputDir = Paths.get(config.othersOutputDir);
        Files.createDirectories(mergedOutputDir);
        Files.createDirectories(othersOutputDir);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        Set<String> uniquePoiIds = new LinkedHashSet<>();
        List<ObjectNode> mergedPois = new ArrayList<>();

        int totalCount = -1;
        int fetchedPages = 0;

        for (int pageNum = config.pageStart; pageNum <= config.maxPages; pageNum++) {
            String url = buildUrl(config, pageNum);
            String body = getUtf8Body(client, url);

            Path rawPageFile = othersOutputDir.resolve(String.format("attractions_page_%03d.json", pageNum));
            Files.writeString(rawPageFile, body, StandardCharsets.UTF_8);

            JsonNode root = MAPPER.readTree(body);
            String status = text(root, "status");
            if (!"1".equals(status)) {
                String info = text(root, "info");
                String infocode = text(root, "infocode");
                throw new IllegalStateException("高德接口返回失败: status=" + status + ", info=" + info + ", infocode=" + infocode);
            }

            if (totalCount < 0) {
                totalCount = root.path("count").asInt(-1);
            }

            ArrayNode pois = asArray(root.path("pois"));
            if (pois == null || pois.isEmpty()) {
                break;
            }

            for (JsonNode poi : pois) {
                ObjectNode normalized = normalizePoi(poi);
                String poiId = normalized.path("poi_id").asText();
                if (!poiId.isBlank() && uniquePoiIds.add(poiId)) {
                    mergedPois.add(normalized);
                }
            }

            fetchedPages++;

            if (pois.size() < config.pageSize) {
                break;
            }

            if (config.sleepMillis > 0) {
                Thread.sleep(config.sleepMillis);
            }
        }

        Path mergedFile = writeMergedJson(mergedOutputDir, mergedPois, batchId);
        writeImportSql(othersOutputDir, mergedPois);
        writeSummary(othersOutputDir, config, fetchedPages, totalCount, mergedPois.size(), mergedFile.getFileName().toString());

        System.out.printf("完成: pages=%d, totalCount=%d, uniquePois=%d, mergedFile=%s, othersOutput=%s%n",
                fetchedPages, totalCount, mergedPois.size(),
                mergedFile.toAbsolutePath(), othersOutputDir.toAbsolutePath());
    }

    private static String getUtf8Body(HttpClient client, String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP请求失败: status=" + response.statusCode() + ", url=" + url);
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static String buildUrl(Config config, int pageNum) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("key", config.key);
        query.put("polygon", config.polygon);
        query.put("types", config.types);
        query.put("page_size", String.valueOf(config.pageSize));
        query.put("page_num", String.valueOf(pageNum));

        StringBuilder sb = new StringBuilder(config.endpoint).append("?");
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

    private static ObjectNode normalizePoi(JsonNode poi) {
        ObjectNode node = MAPPER.createObjectNode();
        putNullable(node, "poi_id", textOrNull(poi, "id"));
        putNullable(node, "parent_poi_id", textOrNull(poi, "parent"));
        putNullable(node, "name", textOrNull(poi, "name"));
        putNullable(node, "address", textOrNull(poi, "address"));
        putNullable(node, "location", textOrNull(poi, "location"));
        putNullable(node, "pcode", textOrNull(poi, "pcode"));
        putNullable(node, "pname", textOrNull(poi, "pname"));
        putNullable(node, "citycode", textOrNull(poi, "citycode"));
        putNullable(node, "cityname", textOrNull(poi, "cityname"));
        putNullable(node, "adcode", textOrNull(poi, "adcode"));
        putNullable(node, "adname", textOrNull(poi, "adname"));
        putNullable(node, "type", textOrNull(poi, "type"));
        putNullable(node, "typecode", textOrNull(poi, "typecode"));
        putNullable(node, "distance", textOrNull(poi, "distance"));
        return node;
    }

    private static Path writeMergedJson(Path outputDir, List<ObjectNode> mergedPois, String batchId) throws IOException {
        ArrayNode arrayNode = MAPPER.createArrayNode();
        mergedPois.forEach(arrayNode::add);
        Path mergedFile = outputDir.resolve("attractions_merged_" + batchId + ".json");
        MAPPER.writeValue(mergedFile.toFile(), arrayNode);
        return mergedFile;
    }

    private static void writeImportSql(Path outputDir, List<ObjectNode> mergedPois) throws IOException {
        Path sqlFile = outputDir.resolve("attractions_import.sql");
        StringBuilder sb = new StringBuilder();
        sb.append("SET NAMES utf8mb4;\n");
        sb.append("START TRANSACTION;\n\n");

        if (!mergedPois.isEmpty()) {
            sb.append("INSERT INTO attractions (");
            sb.append(String.join(", ", DB_COLUMNS));
            sb.append(") VALUES\n");

            for (int i = 0; i < mergedPois.size(); i++) {
                ObjectNode poi = mergedPois.get(i);
                sb.append("(");
                for (int c = 0; c < DB_COLUMNS.size(); c++) {
                    String col = DB_COLUMNS.get(c);
                    JsonNode valueNode = poi.get(col);
                    String value = valueNode == null || valueNode.isNull() ? null : valueNode.asText();
                    sb.append(toSqlValue(value));
                    if (c < DB_COLUMNS.size() - 1) {
                        sb.append(", ");
                    }
                }
                sb.append(")");
                sb.append(i < mergedPois.size() - 1 ? ",\n" : "\n");
            }

            sb.append("ON DUPLICATE KEY UPDATE\n");
            sb.append("parent_poi_id = VALUES(parent_poi_id),\n");
            sb.append("name = VALUES(name),\n");
            sb.append("address = VALUES(address),\n");
            sb.append("location = VALUES(location),\n");
            sb.append("pcode = VALUES(pcode),\n");
            sb.append("pname = VALUES(pname),\n");
            sb.append("citycode = VALUES(citycode),\n");
            sb.append("cityname = VALUES(cityname),\n");
            sb.append("adcode = VALUES(adcode),\n");
            sb.append("adname = VALUES(adname),\n");
            sb.append("type = VALUES(type),\n");
            sb.append("typecode = VALUES(typecode),\n");
            sb.append("distance = VALUES(distance);\n\n");
        }

        sb.append("COMMIT;\n");
        Files.writeString(sqlFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void writeSummary(Path outputDir, Config config, int fetchedPages, int totalCount, int uniqueCount, String mergedFileName) throws IOException {
        String ts = LocalDateTime.now().format(TS);
        Path summaryFile = outputDir.resolve("attractions_fetch_summary_" + ts + ".txt");

        String text = "AMAP batch fetch summary\n"
                + "endpoint=" + config.endpoint + "\n"
                + "polygon=" + config.polygon + "\n"
                + "types=" + config.types + "\n"
                + "pageSize=" + config.pageSize + "\n"
                + "pageStart=" + config.pageStart + "\n"
                + "maxPages=" + config.maxPages + "\n"
                + "fetchedPages=" + fetchedPages + "\n"
                + "reportedTotalCount=" + totalCount + "\n"
                + "uniquePoiCount=" + uniqueCount + "\n"
                + "generatedFiles=others/attractions_page_*.json, content/" + mergedFileName + ", others/attractions_import.sql\n";

        Files.writeString(summaryFile, text, StandardCharsets.UTF_8);
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null || value.isBlank()) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private static ArrayNode asArray(JsonNode node) {
        return node != null && node.isArray() ? (ArrayNode) node : null;
    }

    private static String toSqlValue(String value) {
        if (value == null) {
            return "NULL";
        }
        String escaped = value
                .replace("\\", "\\\\")
                .replace("'", "''");
        return "'" + escaped + "'";
    }

    private static class Config {
        private String endpoint = "https://restapi.amap.com/v5/place/polygon";
        private String key;
        //beijing: "116.217132,40.016218|116.539893,40.01763|116.200533,39.828094|116.49194,39.825261"
        //nanjing: "118.615633,31.977535|118.828269,32.23027|118.828269,32.23027|119.054264,32.11342"
        private String polygon = "116.217132,40.016218|116.539893,40.01763|116.200533,39.828094|116.49194,39.825261";
        //attractions: "110201|110202|110203|110204|110210|140100|140400|140600"
        //transport: "150500|150702|150904"
        private String types = "110201|110202|110203|110204|110210|140100|140400|140600";
        private int pageSize = 25;
        private int pageStart = 1;
        private int maxPages = 1;
        private long sleepMillis = 200;
        private String mergedOutputDir = "src/main/resources/content";
        private String othersOutputDir = "src/main/resources/others";

        static Config fromArgs(String[] args) {
            Config config = new Config();

            for (String arg : args) {
                if (arg == null || arg.isBlank() || !arg.startsWith("--")) {
                    continue;
                }
                int idx = arg.indexOf('=');
                if (idx <= 2 || idx == arg.length() - 1) {
                    continue;
                }
                String key = arg.substring(2, idx);
                String value = arg.substring(idx + 1);
                apply(config, key, value);
            }

            String envKey = System.getenv("AMAP_KEY");
            if (envKey != null && !envKey.isBlank()) {
                config.key = envKey.trim();
            }

            return config;
        }

        private static void apply(Config config, String key, String value) {
            switch (key) {
                case "endpoint" -> config.endpoint = value;
                case "key" -> config.key = value;
                case "polygon" -> config.polygon = value;
                case "types" -> config.types = value;
                case "pageSize" -> config.pageSize = Integer.parseInt(value);
                case "pageStart" -> config.pageStart = Integer.parseInt(value);
                case "maxPages" -> config.maxPages = Integer.parseInt(value);
                case "sleepMillis" -> config.sleepMillis = Long.parseLong(value);
                case "outputDir" -> config.mergedOutputDir = value;
                case "mergedOutputDir" -> config.mergedOutputDir = value;
                case "othersOutputDir" -> config.othersOutputDir = value;
                default -> {
                    // ignore unknown option
                }
            }
        }
    }
}
