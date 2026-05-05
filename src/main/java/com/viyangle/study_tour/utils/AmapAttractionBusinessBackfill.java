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
import java.util.List;

public class AmapAttractionBusinessBackfill {

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
            throw new IllegalStateException("AMAP_KEY is empty.");
        }

        List<String> poiIds = loadPoiIds();
        int updated = 0;
        int skipped = 0;

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            String updateSql = "UPDATE attractions SET opentime_today=?, opentime_week=?, tel=? WHERE poi_id=?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                for (String poiId : poiIds) {
                    BusinessInfo bi = fetchBusinessInfo(amapKey, poiId);
                    if (bi == null) {
                        skipped++;
                        Thread.sleep(150);
                        continue;
                    }
                    ps.setString(1, bi.opentimeToday());
                    ps.setString(2, bi.opentimeWeek());
                    ps.setString(3, bi.tel());
                    ps.setString(4, poiId);
                    updated += ps.executeUpdate();
                    Thread.sleep(150);
                }
            }
        }

        System.out.println("Backfill done. poiCount=" + poiIds.size() + ", updated=" + updated + ", skipped=" + skipped);
    }

    private static List<String> loadPoiIds() throws Exception {
        List<String> list = new ArrayList<>();
        String sql = "SELECT poi_id FROM attractions ORDER BY poi_id";
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(rs.getString("poi_id"));
            }
        }
        return list;
    }

    private static BusinessInfo fetchBusinessInfo(String key, String poiId) {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                String url = "https://restapi.amap.com/v5/place/detail?key="
                        + URLEncoder.encode(key, StandardCharsets.UTF_8)
                        + "&id=" + URLEncoder.encode(poiId, StandardCharsets.UTF_8)
                        + "&show_fields=business";

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 200) {
                    continue;
                }

                JsonNode root = MAPPER.readTree(resp.body());
                String status = root.path("status").asText("");
                if (!"1".equals(status)) {
                    String info = root.path("info").asText("");
                    if (info != null && info.contains("QPS")) {
                        Thread.sleep((i + 1L) * 300L);
                        continue;
                    }
                    return null;
                }

                JsonNode pois = root.path("pois");
                if (!pois.isArray() || pois.isEmpty()) {
                    return new BusinessInfo(null, null, null);
                }
                JsonNode business = pois.get(0).path("business");
                String opentimeToday = textOrNull(business.path("opentime_today"));
                String opentimeWeek = textOrNull(business.path("opentime_week"));
                String tel = textOrNull(business.path("tel"));
                return new BusinessInfo(opentimeToday, opentimeWeek, tel);
            } catch (Exception e) {
                try {
                    Thread.sleep((i + 1L) * 300L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String s = node.asText("");
        return s == null || s.isBlank() ? null : s.trim();
    }

    private record BusinessInfo(String opentimeToday, String opentimeWeek, String tel) {
    }
}
