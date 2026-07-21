package com.viyangle.study_tour.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sync attractions from a UTF-8 AMap JSON file through JDBC parameters.
 *
 * Example:
 * mvn -DskipTests exec:java -Dexec.mainClass="com.viyangle.study_tour.utils.AmapAttractionJsonDbSync" -Dexec.args="--inputFile=src/main/resources/content/attractions_merged_20260721_100255.json --topN=50"
 */
public class AmapAttractionJsonDbSync {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> DB_COLUMNS = List.of(
            "poi_id", "parent_poi_id", "name", "address", "location",
            "pcode", "pname", "citycode", "cityname", "adcode", "adname",
            "type", "typecode", "distance", "opentime_today", "opentime_week", "tel", "status"
    );

    private static final Set<String> BUSINESS_COLUMNS = Set.of(
            "opentime_today", "opentime_week", "tel"
    );

    public static void main(String[] args) throws Exception {
        try {
            Config config = Config.fromArgs(args);
            validate(config);

            List<JsonNode> source = readSource(config);
            if (source.isEmpty()) {
                throw new IllegalArgumentException("No valid POIs found in JSON: " + config.inputFile);
            }

            try (Connection conn = DriverManager.getConnection(config.dbUrl, config.dbUser, config.dbPassword)) {
                conn.setAutoCommit(false);
                ensureStatusColumn(conn);

                if (config.dryRun) {
                    printPlan(conn, source, config);
                    conn.rollback();
                    return;
                }

                RouteOutdatedAttractionStatusUpdater.ensureColumn(conn);
                int upserted = upsertActive(conn, source, config);
                int staleMarked = config.markMissingStale
                        ? markMissingStale(conn, activePoiIds(source), config.staleStatus)
                        : 0;
                int routeFlagsRefreshed = RouteOutdatedAttractionStatusUpdater.refreshAll(conn);
                conn.commit();

                System.out.printf("Done: input=%s, activeSource=%d, upserted=%d, staleMarked=%d, routeFlagsRefreshed=%d%n",
                        Paths.get(config.inputFile).toAbsolutePath(), source.size(), upserted, staleMarked,
                        routeFlagsRefreshed);
            }
        } finally {
            shutdownMysqlCleanupThread();
        }
    }

    private static void validate(Config config) {
        if (blank(config.inputFile)) {
            throw new IllegalArgumentException("inputFile is required.");
        }
        if (!Files.exists(Paths.get(config.inputFile))) {
            throw new IllegalArgumentException("Input file not found: " + Paths.get(config.inputFile).toAbsolutePath());
        }
        if (config.topN <= 0) {
            throw new IllegalArgumentException("topN must be positive.");
        }
        if (blank(config.dbUrl) || blank(config.dbUser)) {
            throw new IllegalArgumentException("Datasource is required from application.yaml or --dbUrl/--dbUser.");
        }
    }

    private static List<JsonNode> readSource(Config config) throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(Paths.get(config.inputFile), StandardCharsets.UTF_8));
        if (!root.isArray()) {
            throw new IllegalArgumentException("Input JSON must be an array: " + config.inputFile);
        }

        List<JsonNode> source = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : root) {
            if (source.size() >= config.topN) {
                break;
            }
            String poiId = columnValue(node, "poi_id", config);
            if (blank(poiId) || !seen.add(poiId)) {
                continue;
            }
            source.add(node);
        }
        return source;
    }

    private static void ensureStatusColumn(Connection conn) throws Exception {
        try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, "attractions", "status")) {
            if (rs.next()) {
                return;
            }
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("ALTER TABLE attractions ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' AFTER tel");
        }
    }

    private static int upsertActive(Connection conn, List<JsonNode> source, Config config) throws Exception {
        String sql = buildUpsertSql(config);
        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (JsonNode node : source) {
                for (int i = 0; i < DB_COLUMNS.size(); i++) {
                    String value = columnValue(node, DB_COLUMNS.get(i), config);
                    if (value == null) {
                        ps.setNull(i + 1, Types.VARCHAR);
                    } else {
                        ps.setString(i + 1, value);
                    }
                }
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        }
        return count;
    }

    private static String buildUpsertSql(Config config) {
        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO attractions (")
                .append(String.join(", ", DB_COLUMNS))
                .append(") VALUES (");
        for (int i = 0; i < DB_COLUMNS.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(") ON DUPLICATE KEY UPDATE ");
        for (int i = 1; i < DB_COLUMNS.size(); i++) {
            String column = DB_COLUMNS.get(i);
            sql.append(column).append(" = ").append(updateExpression(column, config));
            if (i < DB_COLUMNS.size() - 1) {
                sql.append(", ");
            }
        }
        return sql.toString();
    }

    private static String updateExpression(String column, Config config) {
        String valueExpression = "VALUES(" + column + ")";
        if (config.preserveBusinessWhenBlank && BUSINESS_COLUMNS.contains(column)) {
            return "IF(" + valueExpression + " IS NULL OR " + valueExpression + " = '', "
                    + column + ", " + valueExpression + ")";
        }
        return valueExpression;
    }

    private static int markMissingStale(Connection conn, Set<String> activePoiIds, String staleStatus) throws Exception {
        if (activePoiIds.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE attractions SET status = ?")) {
                ps.setString(1, staleStatus);
                return ps.executeUpdate();
            }
        }

        StringBuilder sql = new StringBuilder("UPDATE attractions SET status = ? WHERE poi_id NOT IN (");
        for (int i = 0; i < activePoiIds.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(")");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, staleStatus);
            int idx = 2;
            for (String poiId : activePoiIds) {
                ps.setString(idx++, poiId);
            }
            return ps.executeUpdate();
        }
    }

    private static void printPlan(Connection conn, List<JsonNode> source, Config config) throws Exception {
        Set<String> activeIds = activePoiIds(source);
        Set<String> existingIds = new LinkedHashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT poi_id FROM attractions")) {
            while (rs.next()) {
                existingIds.add(rs.getString("poi_id"));
            }
        }

        int overlap = 0;
        for (String poiId : activeIds) {
            if (existingIds.contains(poiId)) {
                overlap++;
            }
        }
        int stale = 0;
        for (String poiId : existingIds) {
            if (!activeIds.contains(poiId)) {
                stale++;
            }
        }
        System.out.printf("Dry run: activeSource=%d, existing=%d, overlap=%d, new=%d, staleToMark=%d%n",
                activeIds.size(), existingIds.size(), overlap, activeIds.size() - overlap, stale);
    }

    private static Set<String> activePoiIds(List<JsonNode> source) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode node : source) {
            String poiId = firstText(node, "poi_id", "id");
            if (!blank(poiId)) {
                ids.add(poiId);
            }
        }
        return ids;
    }

    private static String columnValue(JsonNode node, String column, Config config) {
        if ("status".equals(column)) {
            return config.activeStatus;
        }
        if ("poi_id".equals(column)) {
            return firstText(node, "poi_id", "id");
        }
        if ("parent_poi_id".equals(column)) {
            return firstText(node, "parent_poi_id", "parent");
        }
        if (BUSINESS_COLUMNS.contains(column)) {
            return textOrNull(node.path("business"), column);
        }
        return textOrNull(node, column);
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = textOrNull(node, field);
            if (!blank(value)) {
                return value;
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText("");
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void shutdownMysqlCleanupThread() {
        try {
            Class<?> cleanup = Class.forName("com.mysql.cj.jdbc.AbandonedConnectionCleanupThread");
            cleanup.getMethod("checkedShutdown").invoke(null);
            cleanup.getMethod("uncheckedShutdown").invoke(null);
        } catch (Exception ignored) {
            // MySQL cleanup is best-effort for clean exec:java shutdown.
        }
    }

    private static class Config {
        private String applicationYaml = "src/main/resources/application.yaml";
        private String inputFile = "src/main/resources/content/attractions_merged_20260721_100255.json";
        private String dbUrl;
        private String dbUser;
        private String dbPassword;
        private int topN = 50;
        private String activeStatus = "ACTIVE";
        private String staleStatus = "STALE";
        private boolean markMissingStale = true;
        private boolean preserveBusinessWhenBlank = true;
        private boolean dryRun = false;

        static Config fromArgs(String[] args) {
            Config config = new Config();
            Map<String, String> argMap = parseArgs(args);
            if (argMap.containsKey("applicationYaml")) {
                config.applicationYaml = argMap.get("applicationYaml");
            }
            applyYamlDefaults(config);
            argMap.forEach((key, value) -> apply(config, key, value));
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
                map.put(arg.substring(2, idx), arg.substring(idx + 1));
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
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to read application.yaml: " + yamlPath.toAbsolutePath(), ex);
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
                case "inputFile" -> config.inputFile = value;
                case "dbUrl" -> config.dbUrl = value;
                case "dbUser" -> config.dbUser = value;
                case "dbPassword" -> config.dbPassword = value;
                case "topN" -> config.topN = Integer.parseInt(value);
                case "activeStatus" -> config.activeStatus = value;
                case "staleStatus" -> config.staleStatus = value;
                case "markMissingStale" -> config.markMissingStale = Boolean.parseBoolean(value);
                case "preserveBusinessWhenBlank" -> config.preserveBusinessWhenBlank = Boolean.parseBoolean(value);
                case "dryRun" -> config.dryRun = Boolean.parseBoolean(value);
                default -> {
                    // ignore unknown option
                }
            }
        }
    }
}
