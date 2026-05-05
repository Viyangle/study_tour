package com.viyangle.study_tour.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
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
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GenerateReferencePairNotesSql {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String DB_URL = "jdbc:mysql://localhost:3306/study_tour?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "123456";

    public static void main(String[] args) throws Exception {
        String amapKey = System.getenv("AMAP_KEY");
        if (amapKey == null || amapKey.isBlank()) {
            amapKey = "41e48a987083acc392a818fc638eafc6";
        }

        List<PairRow> rows = loadPairs();
        Path out = Paths.get("scripts", "update_reference_pair_notes_cn.sql");
        Files.createDirectories(out.getParent());

        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("SET NAMES utf8mb4;\n");
            w.write("START TRANSACTION;\n");

            for (PairRow row : rows) {
                TransitInfo transit = fetchTransit(amapKey, row);
                String openTime = fetchOpenTime(amapKey, row.fromPoiId);
                String notes = summarize(openTime, transit);
                String sql = "UPDATE reference_pairs SET notes='" + escapeSql(notes) + "' WHERE id=" + row.id + ";";
                w.write(sql);
                w.write("\n");
                Thread.sleep(120);
            }

            w.write("COMMIT;\n");
        }

        System.out.println("Generated SQL: " + out.toAbsolutePath());
        System.out.println("Rows: " + rows.size());
    }

    private static List<PairRow> loadPairs() throws Exception {
        List<PairRow> list = new ArrayList<>();
        String sql = """
                SELECT rp.id, rp.from_poi_id, rp.to_poi_id, af.location AS from_loc, at2.location AS to_loc,
                       af.citycode AS from_city, at2.citycode AS to_city
                FROM reference_pairs rp
                JOIN attractions af ON af.poi_id = rp.from_poi_id COLLATE utf8mb4_0900_ai_ci
                JOIN attractions at2 ON at2.poi_id = rp.to_poi_id COLLATE utf8mb4_0900_ai_ci
                ORDER BY rp.id
                """;
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                PairRow row = new PairRow();
                row.id = rs.getLong("id");
                row.fromPoiId = rs.getString("from_poi_id");
                row.toPoiId = rs.getString("to_poi_id");
                row.fromLocation = rs.getString("from_loc");
                row.toLocation = rs.getString("to_loc");
                row.fromCityCode = rs.getString("from_city");
                row.toCityCode = rs.getString("to_city");
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

        StringBuilder url = new StringBuilder("https://restapi.amap.com/v5/direction/transit/integrated?");
        boolean first = true;
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (!first) url.append("&");
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
        TransitInfo info = new TransitInfo();
        if (resp.statusCode() != 200) {
            return info;
        }
        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode route = root.path("route");
        JsonNode transits = route.path("transits");
        info.transitCount = transits.isArray() ? transits.size() : 0;
        if (info.transitCount > 0) {
            JsonNode best = transits.get(0);
            info.walkingDistance = intValue(best.path("walking_distance"));
            info.nightFlag = best.path("nightflag").asText("");
            JsonNode segments = best.path("segments");
            if (segments.isArray()) {
                for (JsonNode seg : segments) {
                    JsonNode busLines = seg.path("bus").path("buslines");
                    if (busLines.isArray() && busLines.size() > 0) {
                        String lineName = busLines.get(0).path("name").asText("");
                        if (lineName != null && !lineName.isBlank()) {
                            info.primaryLine = lineName;
                            break;
                        }
                    }
                }
            }
        }
        return info;
    }

    private static String fetchOpenTime(String key, String poiId) throws Exception {
        String url = "https://restapi.amap.com/v3/place/detail?key=" +
                URLEncoder.encode(key, StandardCharsets.UTF_8) +
                "&id=" + URLEncoder.encode(poiId, StandardCharsets.UTF_8) +
                "&extensions=all";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            return "未知";
        }
        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode pois = root.path("pois");
        if (!pois.isArray() || pois.isEmpty()) {
            return "未知";
        }
        JsonNode business = pois.get(0).path("business");
        String today = business.path("opentime_today").asText("");
        if (!today.isBlank()) {
            return today.trim();
        }
        String week = business.path("opentime_week").asText("");
        if (!week.isBlank()) {
            return week.trim();
        }
        return "未知";
    }

    private static String summarize(String openTime, TransitInfo t) {
        String commute;
        if (t.primaryLine != null && !t.primaryLine.isBlank()) {
            commute = "从这里乘坐" + t.primaryLine + "到下一个景点";
        } else if (t.transitCount > 0) {
            commute = "从这里乘坐公共交通到下一个景点";
        } else {
            commute = "建议从这里打车或包车到下一个景点";
        }

        String extra = "";
        if (t.walkingDistance >= 2500) {
            extra = "，步行距离较长，建议预留机动时间";
        } else if ("1".equals(t.nightFlag)) {
            extra = "，建议优先安排白天出行";
        }
        return "开放时间：" + openTime + "。" + commute + extra + "。";
    }

    private static int intValue(JsonNode n) {
        if (n == null || n.isNull()) return -1;
        if (n.isInt() || n.isLong()) return n.asInt();
        try {
            return Integer.parseInt(n.asText("-1").trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String escapeSql(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    private static class PairRow {
        long id;
        String fromPoiId;
        String toPoiId;
        String fromLocation;
        String toLocation;
        String fromCityCode;
        String toCityCode;
    }

    private static class TransitInfo {
        int transitCount = 0;
        int walkingDistance = -1;
        String nightFlag = "";
        String primaryLine = "";
    }
}
