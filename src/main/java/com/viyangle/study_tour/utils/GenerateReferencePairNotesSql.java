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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backfill reference_pairs.notes with:
 * 1) from/to attraction open time + tel
 * 2) commute suggestion from AMap transit API
 *
 * Run:
 * mvn -DskipTests exec:java "-Dexec.mainClass=com.viyangle.study_tour.utils.GenerateReferencePairNotesSql"
 */
public class GenerateReferencePairNotesSql {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String DB_URL = "jdbc:mysql://localhost:3306/study_tour";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "123456";

    private static final String AMAP_ENDPOINT = "https://restapi.amap.com/v5/direction/transit/integrated";

    public static void main(String[] args) throws Exception {
        String amapKey = System.getenv("AMAP_KEY");
        if (amapKey == null || amapKey.isBlank()) {
            throw new IllegalStateException("AMAP_KEY is required.");
        }
        boolean onlyEmpty = Boolean.parseBoolean(System.getenv().getOrDefault("ONLY_EMPTY_NOTES", "false"));

        List<PairRow> rows = loadPairsForBackfill(onlyEmpty);
        if (rows.isEmpty()) {
            System.out.println("No reference_pairs rows need notes backfill.");
            return;
        }

        int success = 0;
        int failed = 0;

        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            c.setAutoCommit(false);
            for (PairRow row : rows) {
                try {
                    TransitInfo transit = fetchTransit(amapKey, row);
                    String notes = summarizeNotes(row, transit);
                    updateNotes(c, row.id, notes);
                    success++;
                    Thread.sleep(120);
                } catch (Exception e) {
                    failed++;
                    System.err.printf("Backfill failed, id=%d, from=%s, to=%s, err=%s%n",
                            row.id, row.fromPoiId, row.toPoiId, e.getMessage());
                }
            }
            c.commit();
        }

        System.out.printf("Done. total=%d, success=%d, failed=%d%n", rows.size(), success, failed);
    }

    private static List<PairRow> loadPairsForBackfill(boolean onlyEmpty) throws Exception {
        List<PairRow> list = new ArrayList<>();
        String sql = """
                SELECT rp.id,
                       rp.from_poi_id,
                       rp.to_poi_id,
                       af.name AS from_name,
                       af.location AS from_loc,
                       af.opentime_today AS from_open_today,
                       af.opentime_week AS from_open_week,
                       af.tel AS from_tel,
                       at2.name AS to_name,
                       at2.location AS to_loc,
                       at2.opentime_today AS to_open_today,
                       at2.opentime_week AS to_open_week,
                       at2.tel AS to_tel,
                       af.citycode AS from_city,
                       at2.citycode AS to_city
                FROM reference_pairs rp
                JOIN attractions af ON af.poi_id = rp.from_poi_id COLLATE utf8mb4_0900_ai_ci
                JOIN attractions at2 ON at2.poi_id = rp.to_poi_id COLLATE utf8mb4_0900_ai_ci
                %s
                ORDER BY rp.id
                """.formatted(onlyEmpty ? "WHERE rp.notes IS NULL OR TRIM(rp.notes) = ''" : "");

        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                PairRow row = new PairRow();
                row.id = rs.getLong("id");
                row.fromPoiId = rs.getString("from_poi_id");
                row.toPoiId = rs.getString("to_poi_id");
                row.fromName = rs.getString("from_name");
                row.fromLocation = rs.getString("from_loc");
                row.fromOpenToday = rs.getString("from_open_today");
                row.fromOpenWeek = rs.getString("from_open_week");
                row.fromTel = rs.getString("from_tel");
                row.toName = rs.getString("to_name");
                row.toLocation = rs.getString("to_loc");
                row.toOpenToday = rs.getString("to_open_today");
                row.toOpenWeek = rs.getString("to_open_week");
                row.toTel = rs.getString("to_tel");
                row.fromCityCode = rs.getString("from_city");
                row.toCityCode = rs.getString("to_city");
                if (blank(row.fromPoiId) || blank(row.toPoiId) || blank(row.fromLocation) || blank(row.toLocation)) {
                    continue;
                }
                list.add(row);
            }
        }
        return list;
    }

    private static TransitInfo fetchTransit(String key, PairRow row) throws Exception {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("key", key);
        q.put("origin", row.fromLocation);
        q.put("destination", row.toLocation);
        q.put("originpoi", row.fromPoiId);
        q.put("destinationpoi", row.toPoiId);
        q.put("city1", row.fromCityCode == null ? "" : row.fromCityCode);
        q.put("city2", row.toCityCode == null ? "" : row.toCityCode);

        StringBuilder url = new StringBuilder(AMAP_ENDPOINT).append("?");
        boolean first = true;
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (!first) {
                url.append("&");
            }
            url.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            url.append("=");
            url.append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            first = false;
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            return TransitInfo.empty();
        }

        JsonNode root = MAPPER.readTree(resp.body());
        TransitInfo info = new TransitInfo();
        JsonNode route = root.path("route");
        JsonNode transits = route.path("transits");
        info.transitCount = transits.isArray() ? transits.size() : 0;
        if (info.transitCount > 0) {
            JsonNode best = transits.get(0);
            info.bestTransitDistanceM = intValue(best.path("distance"));
            info.bestWalkingDistanceM = intValue(best.path("walking_distance"));
            info.bestNightFlag = best.path("nightflag").asText("");
            info.lineNames = extractLineNames(best.path("segments"));
        }
        return info;
    }

    private static void updateNotes(Connection c, long id, String notes) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("UPDATE reference_pairs SET notes = ? WHERE id = ?")) {
            ps.setString(1, notes);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private static String summarizeNotes(PairRow row, TransitInfo t) {
        StringBuilder sb = new StringBuilder();
        sb.append("起点“").append(nvl(row.fromName, row.fromPoiId)).append("”开放时间：")
                .append(formatOpenTime(row.fromOpenToday, row.fromOpenWeek))
                .append("，电话：").append(formatTel(row.fromTel)).append("。");
        sb.append("终点“").append(nvl(row.toName, row.toPoiId)).append("”开放时间：")
                .append(formatOpenTime(row.toOpenToday, row.toOpenWeek))
                .append("，电话：").append(formatTel(row.toTel)).append("。");

        if (t.transitCount <= 0) {
            sb.append("通勤建议：从当前景点打车前往下一个景点。");
            return sb.toString();
        }

        if (!t.lineNames.isEmpty()) {
            sb.append("通勤建议：乘坐");
            int top = Math.min(2, t.lineNames.size());
            for (int i = 0; i < top; i++) {
                if (i > 0) {
                    sb.append("，换乘");
                }
                sb.append(t.lineNames.get(i));
            }
            sb.append("前往下一个景点");
        } else {
            sb.append("通勤建议：乘坐公交或地铁前往下一个景点");
        }

        boolean hasTransitDistance = t.bestTransitDistanceM > 0;
        boolean hasWalkingDistance = t.bestWalkingDistanceM > 0;
        if (hasTransitDistance || hasWalkingDistance) {
            sb.append("（");
            if (hasTransitDistance) {
                sb.append("公共交通里程约").append(t.bestTransitDistanceM).append("米");
            }
            if (hasTransitDistance && hasWalkingDistance) {
                sb.append("，");
            }
            if (hasWalkingDistance) {
                sb.append("步行约").append(t.bestWalkingDistanceM).append("米");
            }
            sb.append("）");
        }
        if ("1".equals(t.bestNightFlag)) {
            sb.append("，夜间方案受限，建议尽量白天出行");
        }
        sb.append("。");
        return sb.toString();
    }

    private static List<String> extractLineNames(JsonNode segments) {
        List<String> lines = new ArrayList<>();
        if (!segments.isArray()) {
            return lines;
        }
        for (JsonNode segment : segments) {
            JsonNode busLines = segment.path("bus").path("buslines");
            if (!busLines.isArray() || busLines.isEmpty()) {
                continue;
            }
            String line = busLines.get(0).path("name").asText("").trim();
            if (!line.isEmpty() && !lines.contains(line)) {
                lines.add(line);
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

    private static String formatOpenTime(String openToday, String openWeek) {
        if (!blank(openToday)) {
            return openToday.trim();
        }
        if (!blank(openWeek)) {
            return openWeek.trim();
        }
        return "未知";
    }

    private static String formatTel(String tel) {
        return blank(tel) ? "未知" : tel.trim();
    }

    private static String nvl(String value, String fallback) {
        if (!blank(value)) {
            return value.trim();
        }
        return blank(fallback) ? "未知景点" : fallback.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static class PairRow {
        long id;
        String fromPoiId;
        String toPoiId;
        String fromName;
        String fromLocation;
        String fromOpenToday;
        String fromOpenWeek;
        String fromTel;
        String toName;
        String toLocation;
        String toOpenToday;
        String toOpenWeek;
        String toTel;
        String fromCityCode;
        String toCityCode;
    }

    private static class TransitInfo {
        int transitCount = 0;
        int bestTransitDistanceM = -1;
        int bestWalkingDistanceM = -1;
        String bestNightFlag = "";
        List<String> lineNames = List.of();

        static TransitInfo empty() {
            return new TransitInfo();
        }
    }
}
