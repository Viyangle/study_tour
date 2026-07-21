package com.viyangle.study_tour.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Types;
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
 * 批量抓取高德 polygon 景点数据，并输出/入库标准化景点数据。
 *
 * 默认会：
 * 1) 抓取 polygon POI 列表，保留每页原始响应到 src/main/resources/others
 * 2) 调用 detail 接口补齐 business 字段
 * 3) 输出 content/attractions_merged_*.json
 * 4) 输出 others/attractions_import.sql
 *
 * 如需直接写入 attractions 表，加 --syncDb=true。数据库连接默认读取 src/main/resources/application.yaml。
 *
 * 运行示例：
 * mvn -DskipTests exec:java -Dexec.mainClass="com.viyangle.study_tour.utils.AmapAttractionBatchExporter" -Dexec.args="--syncDb=true"
 */
public class AmapAttractionBatchExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final List<String> DB_COLUMNS = List.of(
            "poi_id", "parent_poi_id", "name", "address", "location",
            "pcode", "pname", "citycode", "cityname", "adcode", "adname",
            "type", "typecode", "distance", "opentime_today", "opentime_week", "tel", "status"
    );

    private static final List<String> BUSINESS_COLUMNS = List.of(
            "opentime_today", "opentime_week", "tel"
    );

    public static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        validate(config);

        String batchId = LocalDateTime.now().format(TS);
        Path mergedOutputDir = Paths.get(config.mergedOutputDir);
        Path othersOutputDir = Paths.get(config.othersOutputDir);
        if (config.writeMergedJson) {
            Files.createDirectories(mergedOutputDir);
        }
        if (config.writeImportSql || config.saveRawPages || config.writeSummary) {
            Files.createDirectories(othersOutputDir);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds))
                .build();

        Set<String> uniquePoiIds = new LinkedHashSet<>();
        List<ObjectNode> mergedPois = new ArrayList<>();

        int totalCount = -1;
        int fetchedPages = 0;

        for (int pageNum = config.pageStart; pageNum <= config.maxPages; pageNum++) {
            String url = buildPolygonUrl(config, pageNum);
            String body = getUtf8Body(client, url, config.requestTimeoutSeconds);

            if (config.saveRawPages) {
                Path rawPageFile = othersOutputDir.resolve(String.format("attractions_page_%03d.json", pageNum));
                Files.writeString(rawPageFile, body, StandardCharsets.UTF_8);
            }

            JsonNode root = MAPPER.readTree(body);
            String status = text(root, "status");
            if (!"1".equals(status)) {
                String info = text(root, "info");
                String infocode = text(root, "infocode");
                throw new IllegalStateException("高德 polygon 接口返回失败: status=" + status
                        + ", info=" + info + ", infocode=" + infocode);
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
                String poiId = normalized.path("poi_id").asText("");
                if (!poiId.isBlank() && uniquePoiIds.add(poiId)) {
                    mergedPois.add(normalized);
                }
            }

            fetchedPages++;

            if (pois.size() < config.pageSize) {
                break;
            }

            sleep(config.sleepMillis);
        }

        int detailUpdated = 0;
        if (config.refreshBusinessByDetail) {
            detailUpdated = refreshBusinessFromDetail(client, mergedPois, config);
        }

        Path mergedFile = null;
        if (config.writeMergedJson) {
            mergedFile = writeMergedJson(mergedOutputDir, mergedPois, batchId);
        }
        Path importSqlFile = null;
        if (config.writeImportSql) {
            importSqlFile = writeImportSql(othersOutputDir, mergedPois, config);
        }

        int dbSynced = 0;
        if (config.syncDb) {
            dbSynced = upsertAttractions(config, mergedPois);
        }

        if (config.writeSummary) {
            writeSummary(othersOutputDir, config, fetchedPages, totalCount, mergedPois.size(),
                    detailUpdated, dbSynced, mergedFile, importSqlFile);
        }

        System.out.printf("完成: pages=%d, totalCount=%d, uniquePois=%d, detailUpdated=%d, dbSynced=%d, mergedFile=%s, importSql=%s%n",
                fetchedPages, totalCount, mergedPois.size(), detailUpdated, dbSynced,
                mergedFile == null ? "(disabled)" : mergedFile.toAbsolutePath(),
                importSqlFile == null ? "(disabled)" : importSqlFile.toAbsolutePath());
    }

    private static void validate(Config config) {
        if (config.key == null || config.key.isBlank()) {
            throw new IllegalArgumentException("AMAP key required: --key=... or AMAP_KEY/app.amap.key");
        }
        if (config.polygon == null || config.polygon.isBlank()) {
            throw new IllegalArgumentException("polygon is required.");
        }
        if (config.types == null || config.types.isBlank()) {
            throw new IllegalArgumentException("types is required.");
        }
        if (config.pageSize <= 0 || config.pageSize > 25) {
            throw new IllegalArgumentException("高德 polygon pageSize 需要在 1-25 之间: " + config.pageSize);
        }
        if (config.maxPages < config.pageStart) {
            throw new IllegalArgumentException("maxPages must be >= pageStart.");
        }
        if (config.syncDb) {
            if (blank(config.dbUrl) || blank(config.dbUser)) {
                throw new IllegalArgumentException("syncDb=true requires datasource url/user from application.yaml or --dbUrl/--dbUser.");
            }
        }
    }

    private static String buildPolygonUrl(Config config, int pageNum) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("key", config.key);
        query.put("polygon", config.polygon);
        query.put("types", config.types);
        query.put("show_fields", config.showFields);
        query.put("page_size", String.valueOf(config.pageSize));
        query.put("page_num", String.valueOf(pageNum));
        return buildUrl(config.endpoint, query);
    }

    private static String buildDetailUrl(Config config, String poiId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("key", config.key);
        query.put("id", poiId);
        query.put("show_fields", config.showFields);
        return buildUrl(config.detailEndpoint, query);
    }

    private static String buildUrl(String endpoint, Map<String, String> query) {
        StringBuilder sb = new StringBuilder(endpoint).append("?");
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
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

    private static String getUtf8Body(HttpClient client, String url, int timeoutSeconds) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP 请求失败: status=" + response.statusCode() + ", url=" + url);
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static ObjectNode normalizePoi(JsonNode poi) {
        ObjectNode node = MAPPER.createObjectNode();
        putNullable(node, "poi_id", firstTextOrNull(poi, "poi_id", "id"));
        putNullable(node, "parent_poi_id", firstTextOrNull(poi, "parent_poi_id", "parent"));
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
        setBusiness(node, normalizeBusiness(poi.path("business")));
        return node;
    }

    private static ObjectNode normalizeBusiness(JsonNode business) {
        ObjectNode node = MAPPER.createObjectNode();
        if (business != null && business.isObject()) {
            business.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value != null && !value.isNull()) {
                    String text = value.asText("");
                    if (!text.isBlank()) {
                        node.put(entry.getKey(), text.trim());
                    }
                }
            });
        }
        return node;
    }

    private static int refreshBusinessFromDetail(HttpClient client, List<ObjectNode> pois, Config config) throws Exception {
        int updated = 0;
        int index = 0;
        for (ObjectNode poi : pois) {
            index++;
            String poiId = poi.path("poi_id").asText("");
            if (poiId.isBlank()) {
                continue;
            }

            ObjectNode business = fetchDetailBusiness(client, config, poiId);
            if (business != null && !business.isEmpty()) {
                mergeBusiness(poi, business);
                updated++;
            }

            if (config.detailLogEvery > 0 && index % config.detailLogEvery == 0) {
                System.out.printf("detail progress: %d/%d%n", index, pois.size());
            }
            sleep(config.detailSleepMillis);
        }
        return updated;
    }

    private static ObjectNode fetchDetailBusiness(HttpClient client, Config config, String poiId) throws Exception {
        int retries = Math.max(0, config.detailMaxRetries);
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                String body = getUtf8Body(client, buildDetailUrl(config, poiId), config.requestTimeoutSeconds);
                JsonNode root = MAPPER.readTree(body);
                String status = text(root, "status");
                if ("1".equals(status)) {
                    JsonNode pois = root.path("pois");
                    if (!pois.isArray() || pois.isEmpty()) {
                        return null;
                    }
                    return normalizeBusiness(pois.get(0).path("business"));
                }

                String info = text(root, "info");
                if (attempt < retries && info.contains("QPS")) {
                    sleep((attempt + 1L) * Math.max(300L, config.detailSleepMillis));
                    continue;
                }
                System.out.printf("detail skipped: poiId=%s, status=%s, info=%s, infocode=%s%n",
                        poiId, status, info, text(root, "infocode"));
                return null;
            } catch (Exception ex) {
                if (attempt < retries) {
                    sleep((attempt + 1L) * Math.max(300L, config.detailSleepMillis));
                    continue;
                }
                System.out.printf("detail skipped: poiId=%s, error=%s%n",
                        poiId, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                return null;
            }
        }
        return null;
    }

    private static void mergeBusiness(ObjectNode poi, ObjectNode detailBusiness) {
        ObjectNode merged = businessObject(poi);
        detailBusiness.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && !value.isNull()) {
                String text = value.asText("");
                if (!text.isBlank()) {
                    merged.put(entry.getKey(), text.trim());
                }
            }
        });
        setBusiness(poi, merged);
    }

    private static ObjectNode businessObject(ObjectNode poi) {
        JsonNode business = poi.path("business");
        if (business.isObject()) {
            return (ObjectNode) business.deepCopy();
        }
        return MAPPER.createObjectNode();
    }

    private static void setBusiness(ObjectNode poi, ObjectNode business) {
        if (business == null || business.isEmpty()) {
            poi.putNull("business");
        } else {
            poi.set("business", business);
        }
    }

    private static Path writeMergedJson(Path outputDir, List<ObjectNode> mergedPois, String batchId) throws IOException {
        ArrayNode arrayNode = MAPPER.createArrayNode();
        mergedPois.forEach(arrayNode::add);
        Path mergedFile = outputDir.resolve("attractions_merged_" + batchId + ".json");
        MAPPER.writeValue(mergedFile.toFile(), arrayNode);
        return mergedFile;
    }

    private static Path writeImportSql(Path outputDir, List<ObjectNode> mergedPois, Config config) throws IOException {
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
                    sb.append(toSqlValue(dbColumnValue(poi, col)));
                    if (c < DB_COLUMNS.size() - 1) {
                        sb.append(", ");
                    }
                }
                sb.append(")");
                sb.append(i < mergedPois.size() - 1 ? ",\n" : "\n");
            }

            sb.append("ON DUPLICATE KEY UPDATE\n");
            for (int i = 1; i < DB_COLUMNS.size(); i++) {
                String col = DB_COLUMNS.get(i);
                sb.append(col).append(" = ").append(upsertValueExpression(col, config));
                sb.append(i < DB_COLUMNS.size() - 1 ? ",\n" : ";\n\n");
            }

            sb.append(RouteOutdatedAttractionStatusUpdater.refreshSql()).append(";\n\n");
        }

        sb.append("COMMIT;\n");
        Files.writeString(sqlFile, sb.toString(), StandardCharsets.UTF_8);
        return sqlFile;
    }

    private static int upsertAttractions(Config config, List<ObjectNode> pois) throws Exception {
        if (pois.isEmpty()) {
            return 0;
        }

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO attractions (")
                .append(String.join(", ", DB_COLUMNS))
                .append(") VALUES (");
        for (int i = 0; i < DB_COLUMNS.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(") ON DUPLICATE KEY UPDATE ");
        for (int i = 1; i < DB_COLUMNS.size(); i++) {
            String col = DB_COLUMNS.get(i);
            sql.append(col).append(" = ").append(upsertValueExpression(col, config));
            if (i < DB_COLUMNS.size() - 1) {
                sql.append(", ");
            }
        }

        int synced = 0;
        try (Connection conn = DriverManager.getConnection(config.dbUrl, config.dbUser, config.dbPassword)) {
            conn.setAutoCommit(false);
            RouteOutdatedAttractionStatusUpdater.ensureColumn(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (ObjectNode poi : pois) {
                    for (int i = 0; i < DB_COLUMNS.size(); i++) {
                        String value = dbColumnValue(poi, DB_COLUMNS.get(i));
                        if (value == null) {
                            ps.setNull(i + 1, Types.VARCHAR);
                        } else {
                            ps.setString(i + 1, value);
                        }
                    }
                    ps.addBatch();
                    synced++;
                }
                ps.executeBatch();
            }
            RouteOutdatedAttractionStatusUpdater.refreshAll(conn);
            conn.commit();
        }
        return synced;
    }

    private static void writeSummary(Path outputDir,
                                     Config config,
                                     int fetchedPages,
                                     int totalCount,
                                     int uniqueCount,
                                     int detailUpdated,
                                     int dbSynced,
                                     Path mergedFile,
                                     Path importSqlFile) throws IOException {
        String ts = LocalDateTime.now().format(TS);
        Path summaryFile = outputDir.resolve("attractions_fetch_summary_" + ts + ".txt");

        String text = "AMAP attraction fetch summary\n"
                + "endpoint=" + config.endpoint + "\n"
                + "detailEndpoint=" + config.detailEndpoint + "\n"
                + "polygon=" + config.polygon + "\n"
                + "types=" + config.types + "\n"
                + "showFields=" + config.showFields + "\n"
                + "pageSize=" + config.pageSize + "\n"
                + "pageStart=" + config.pageStart + "\n"
                + "maxPages=" + config.maxPages + "\n"
                + "fetchedPages=" + fetchedPages + "\n"
                + "reportedTotalCount=" + totalCount + "\n"
                + "uniquePoiCount=" + uniqueCount + "\n"
                + "refreshBusinessByDetail=" + config.refreshBusinessByDetail + "\n"
                + "preserveDbBusinessWhenBlank=" + config.preserveDbBusinessWhenBlank + "\n"
                + "detailUpdated=" + detailUpdated + "\n"
                + "syncDb=" + config.syncDb + "\n"
                + "dbUrl=" + nullToEmpty(config.dbUrl) + "\n"
                + "dbSynced=" + dbSynced + "\n"
                + "mergedFile=" + (mergedFile == null ? "" : mergedFile.toAbsolutePath()) + "\n"
                + "importSqlFile=" + (importSqlFile == null ? "" : importSqlFile.toAbsolutePath()) + "\n";

        Files.writeString(summaryFile, text, StandardCharsets.UTF_8);
    }

    private static String dbColumnValue(ObjectNode poi, String column) {
        if ("status".equals(column)) {
            return "ACTIVE";
        }
        if (BUSINESS_COLUMNS.contains(column)) {
            JsonNode business = poi.path("business");
            if (business.isObject()) {
                return textOrNull(business, column);
            }
            return null;
        }
        return textOrNull(poi, column);
    }

    private static String upsertValueExpression(String column, Config config) {
        String valueExpression = "VALUES(" + column + ")";
        if (!config.preserveDbBusinessWhenBlank || !BUSINESS_COLUMNS.contains(column)) {
            return valueExpression;
        }
        return "IF(" + valueExpression + " IS NULL OR " + valueExpression + " = '', "
                + column + ", " + valueExpression + ")";
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null || value.isBlank()) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String firstTextOrNull(JsonNode node, String... fields) {
        if (fields == null) {
            return null;
        }
        for (String field : fields) {
            String value = textOrNull(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s.trim();
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

    private static void sleep(long millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static class Config {
        private String applicationYaml = "src/main/resources/application.yaml";
        private String endpoint = "https://restapi.amap.com/v5/place/polygon";
        private String detailEndpoint = "https://restapi.amap.com/v5/place/detail";
        private String key;
        private String dbUrl;
        private String dbUser;
        private String dbPassword;
        // beijing: "116.217132,40.016218|116.539893,40.01763|116.200533,39.828094|116.49194,39.825261"
        // nanjing: "118.615633,31.977535|118.828269,32.23027|118.828269,32.23027|119.054264,32.11342"
        private String polygon = "118.615633,31.977535|118.828269,32.23027|118.828269,32.23027|119.054264,32.11342";
        // attractions: "110201|110202|110203|110204|110210|140100|140400|140600"
        // transport: "150500|150702|150904"
        private String types = "110201|110202|110203|110204|110210|140100|140400|140600";
        private String showFields = "business";
        private int pageSize = 25;
        private int pageStart = 1;
        private int maxPages = 2;
        private int connectTimeoutSeconds = 15;
        private int requestTimeoutSeconds = 25;
        private long sleepMillis = 200;
        private long detailSleepMillis = 150;
        private int detailMaxRetries = 2;
        private int detailLogEvery = 10;
        private boolean refreshBusinessByDetail = true;
        private boolean preserveDbBusinessWhenBlank = true;
        private boolean syncDb = false;
        private boolean writeMergedJson = true;
        private boolean writeImportSql = true;
        private boolean writeSummary = true;
        private boolean saveRawPages = true;
        private String mergedOutputDir = "src/main/resources/content";
        private String othersOutputDir = "src/main/resources/others";

        static Config fromArgs(String[] args) {
            Config config = new Config();
            Map<String, String> argMap = parseArgs(args);
            if (argMap.containsKey("applicationYaml")) {
                config.applicationYaml = argMap.get("applicationYaml");
            }

            applyYamlDefaults(config);
            argMap.forEach((key, value) -> apply(config, key, value));

            String envKey = System.getenv("AMAP_KEY");
            if ((config.key == null || config.key.isBlank()) && envKey != null && !envKey.isBlank()) {
                config.key = envKey.trim();
            }

            return config;
        }

        private static Map<String, String> parseArgs(String[] args) {
            Map<String, String> map = new LinkedHashMap<>();
            if (args == null) {
                return map;
            }
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
                map.put(key, value);
            }
            return map;
        }

        private static void applyYamlDefaults(Config config) {
            Path yamlPath = Paths.get(config.applicationYaml);
            if (!Files.exists(yamlPath)) {
                return;
            }
            try (InputStream in = Files.newInputStream(yamlPath)) {
                Object loaded = new Yaml().load(in);
                if (!(loaded instanceof Map<?, ?> root)) {
                    return;
                }

                config.dbUrl = resolvePlaceholders(valueAt(root, "spring", "datasource", "url"));
                config.dbUser = resolvePlaceholders(valueAt(root, "spring", "datasource", "username"));
                config.dbPassword = resolvePlaceholders(valueAt(root, "spring", "datasource", "password"));

                String amapKey = resolvePlaceholders(valueAt(root, "app", "amap", "key"));
                if (amapKey != null && !amapKey.isBlank()) {
                    config.key = amapKey;
                }
            } catch (Exception ex) {
                throw new IllegalStateException("读取 application.yaml 失败: " + yamlPath.toAbsolutePath(), ex);
            }
        }

        private static String valueAt(Map<?, ?> root, String... path) {
            Object current = root;
            for (String key : path) {
                if (!(current instanceof Map<?, ?> map)) {
                    return null;
                }
                current = map.get(key);
                if (current == null) {
                    return null;
                }
            }
            return String.valueOf(current);
        }

        private static String resolvePlaceholders(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            if (!trimmed.startsWith("${") || !trimmed.endsWith("}")) {
                return trimmed;
            }
            String body = trimmed.substring(2, trimmed.length() - 1);
            int idx = body.indexOf(':');
            String name = idx >= 0 ? body.substring(0, idx) : body;
            String defaultValue = idx >= 0 ? body.substring(idx + 1) : "";
            String envValue = System.getenv(name);
            return envValue == null || envValue.isBlank() ? defaultValue : envValue.trim();
        }

        private static void apply(Config config, String key, String value) {
            switch (key) {
                case "applicationYaml" -> config.applicationYaml = value;
                case "endpoint" -> config.endpoint = value;
                case "detailEndpoint" -> config.detailEndpoint = value;
                case "key" -> config.key = value;
                case "dbUrl" -> config.dbUrl = value;
                case "dbUser" -> config.dbUser = value;
                case "dbPassword" -> config.dbPassword = value;
                case "polygon" -> config.polygon = value;
                case "types" -> config.types = value;
                case "showFields" -> config.showFields = value;
                case "pageSize" -> config.pageSize = Integer.parseInt(value);
                case "pageStart" -> config.pageStart = Integer.parseInt(value);
                case "maxPages" -> config.maxPages = Integer.parseInt(value);
                case "connectTimeoutSeconds" -> config.connectTimeoutSeconds = Integer.parseInt(value);
                case "requestTimeoutSeconds" -> config.requestTimeoutSeconds = Integer.parseInt(value);
                case "sleepMillis" -> config.sleepMillis = Long.parseLong(value);
                case "detailSleepMillis" -> config.detailSleepMillis = Long.parseLong(value);
                case "detailMaxRetries" -> config.detailMaxRetries = Integer.parseInt(value);
                case "detailLogEvery" -> config.detailLogEvery = Integer.parseInt(value);
                case "refreshBusinessByDetail" -> config.refreshBusinessByDetail = Boolean.parseBoolean(value);
                case "preserveDbBusinessWhenBlank" -> config.preserveDbBusinessWhenBlank = Boolean.parseBoolean(value);
                case "syncDb" -> config.syncDb = Boolean.parseBoolean(value);
                case "writeMergedJson" -> config.writeMergedJson = Boolean.parseBoolean(value);
                case "writeImportSql" -> config.writeImportSql = Boolean.parseBoolean(value);
                case "writeSummary" -> config.writeSummary = Boolean.parseBoolean(value);
                case "saveRawPages" -> config.saveRawPages = Boolean.parseBoolean(value);
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
