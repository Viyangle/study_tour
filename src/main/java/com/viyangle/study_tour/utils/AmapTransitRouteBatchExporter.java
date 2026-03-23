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
import java.util.List;
import java.util.Map;

/**
 * Batch fetch transit routes between POIs from one content json.
 *
 * Example:
 * mvn -DskipTests exec:java -Dexec.mainClass="com.viyangle.study_tour.utils.AmapTransitRouteBatchExporter" -Dexec.args="--key=YOUR_KEY --inputFile=src/main/resources/content/attractions_merged_20260323_170243.json --maxPoi=20"
 */
public class AmapTransitRouteBatchExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        validate(config);

        Path inputFile = Paths.get(config.inputFile);
        Path contentDir = Paths.get(config.contentOutputDir);
        Path othersDir = Paths.get(config.othersOutputDir);
        Files.createDirectories(contentDir);
        if (config.saveRawToOthers) {
            Files.createDirectories(othersDir);
        }

        List<Poi> pois = readPois(inputFile, config.maxPoi);
        if (pois.size() < 2) {
            throw new IllegalArgumentException("Need at least 2 valid POIs, found: " + pois.size());
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        ArrayNode edges = MAPPER.createArrayNode();
        ArrayNode errors = MAPPER.createArrayNode();

        int total = computePairCount(pois.size(), config.bothDirections);
        int done = 0;

        for (int i = 0; i < pois.size(); i++) {
            for (int j = 0; j < pois.size(); j++) {
                if (i == j) {
                    continue;
                }
                if (!config.bothDirections && j <= i) {
                    continue;
                }

                Poi from = pois.get(i);
                Poi to = pois.get(j);
                done++;
                System.out.printf("[%d/%d] %s -> %s%n", done, total, from.poiId, to.poiId);

                String url = buildUrl(config, from, to);
                try {
                    String body = getUtf8Body(client, url, config.timeoutSeconds);
                    JsonNode resp = MAPPER.readTree(body);

                    if (config.saveRawToOthers) {
                        String rawName = String.format("transit_raw_%s_to_%s.json", from.poiId, to.poiId);
                        Files.writeString(othersDir.resolve(rawName), body, StandardCharsets.UTF_8);
                    }

                    ObjectNode edge = summarizeRoute(from, to, resp, config.includeRawResponse);
                    edges.add(edge);
                } catch (Exception ex) {
                    ObjectNode err = MAPPER.createObjectNode();
                    err.put("from_poi_id", from.poiId);
                    err.put("to_poi_id", to.poiId);
                    err.put("message", ex.getMessage() == null ? "unknown error" : ex.getMessage());
                    errors.add(err);
                }

                if (config.sleepMillis > 0) {
                    Thread.sleep(config.sleepMillis);
                }
            }
        }

        ObjectNode output = MAPPER.createObjectNode();
        output.put("generated_at", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        output.put("source_file", inputFile.toAbsolutePath().toString());
        output.put("total_pois", pois.size());
        output.put("both_directions", config.bothDirections);
        output.put("total_requests", total);
        output.put("success_count", edges.size());
        output.put("failed_count", errors.size());
        output.set("routes", edges);
        output.set("errors", errors);

        Path out = contentDir.resolve("transit_routes_" + LocalDateTime.now().format(TS) + ".json");
        MAPPER.writeValue(out.toFile(), output);
        System.out.printf("Done: requests=%d, success=%d, failed=%d, output=%s%n",
                total, edges.size(), errors.size(), out.toAbsolutePath());
    }

    private static void validate(Config config) {
        if (config.key == null || config.key.isBlank()) {
            throw new IllegalArgumentException("AMap key required: --key=... or AMAP_KEY");
        }
        Path input = Paths.get(config.inputFile);
        if (!Files.exists(input)) {
            throw new IllegalArgumentException("Input file not found: " + input.toAbsolutePath());
        }
    }

    private static List<Poi> readPois(Path inputFile, int maxPoi) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readString(inputFile, StandardCharsets.UTF_8));
        if (!root.isArray()) {
            throw new IllegalArgumentException("Input JSON must be an array: " + inputFile);
        }
        List<Poi> pois = new ArrayList<>();
        for (JsonNode node : root) {
            String poiId = textOrNull(node, "poi_id");
            String location = textOrNull(node, "location");
            if (poiId == null || location == null) {
                continue;
            }
            String[] parts = location.split(",");
            if (parts.length != 2) {
                continue;
            }
            String lng = parts[0].trim();
            String lat = parts[1].trim();
            if (lng.isBlank() || lat.isBlank()) {
                continue;
            }
            Poi poi = new Poi();
            poi.poiId = poiId;
            poi.name = textOrEmpty(node, "name");
            poi.location = lng + "," + lat;
            poi.cityCode = textOrEmpty(node, "citycode");
            pois.add(poi);
            if (maxPoi > 0 && pois.size() >= maxPoi) {
                break;
            }
        }
        return pois;
    }

    private static int computePairCount(int n, boolean bothDirections) {
        if (bothDirections) {
            return n * (n - 1);
        }
        return n * (n - 1) / 2;
    }

    private static String buildUrl(Config config, Poi from, Poi to) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("key", config.key);
        query.put("origin", from.location);
        query.put("destination", to.location);
        query.put("originpoi", from.poiId);
        query.put("destinationpoi", to.poiId);
        query.put("city1", from.cityCode);
        query.put("city2", to.cityCode);

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

    private static String getUtf8Body(HttpClient client, String url, int timeoutSeconds) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP failed: status=" + response.statusCode() + ", url=" + url);
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static ObjectNode summarizeRoute(Poi from, Poi to, JsonNode resp, boolean includeRaw) {
        ObjectNode edge = MAPPER.createObjectNode();
        edge.put("from_poi_id", from.poiId);
        edge.put("from_name", from.name);
        edge.put("to_poi_id", to.poiId);
        edge.put("to_name", to.name);
        edge.put("city1", from.cityCode);
        edge.put("city2", to.cityCode);
        edge.put("status", textOrEmpty(resp, "status"));
        edge.put("infocode", textOrEmpty(resp, "infocode"));
        edge.put("info", textOrEmpty(resp, "info"));

        JsonNode route = resp.path("route");
        edge.put("route_distance_m", parseInt(route.path("distance").asText("0")));

        JsonNode transits = route.path("transits");
        edge.put("transit_count", transits.isArray() ? transits.size() : 0);

        JsonNode best = null;
        if (transits.isArray() && transits.size() > 0) {
            best = transits.get(0);
            edge.put("best_transit_distance_m", parseInt(best.path("distance").asText("0")));
            edge.put("best_walking_distance_m", parseInt(best.path("walking_distance").asText("0")));
            edge.put("best_nightflag", best.path("nightflag").asText(""));
            edge.set("best_lines", extractLineNames(best.path("segments")));
        } else {
            edge.put("best_transit_distance_m", -1);
            edge.put("best_walking_distance_m", -1);
            edge.put("best_nightflag", "");
            edge.set("best_lines", MAPPER.createArrayNode());
        }

        if (includeRaw) {
            edge.set("raw", resp);
        }
        return edge;
    }

    private static ArrayNode extractLineNames(JsonNode segments) {
        ArrayNode names = MAPPER.createArrayNode();
        if (!segments.isArray()) {
            return names;
        }
        for (JsonNode segment : segments) {
            JsonNode buslines = segment.path("bus").path("buslines");
            if (!buslines.isArray()) {
                continue;
            }
            for (JsonNode line : buslines) {
                String name = line.path("name").asText("").trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("");
    }

    private static class Poi {
        private String poiId;
        private String name;
        private String location;
        private String cityCode;
    }

    private static class Config {
        private String endpoint = "https://restapi.amap.com/v5/direction/transit/integrated";
        private String key;
        private String inputFile = "src/main/resources/content/attractions_merged_20260323_170243.json";
        private String contentOutputDir = "src/main/resources/content";
        private String othersOutputDir = "src/main/resources/others";
        private int timeoutSeconds = 25;
        private long sleepMillis = 120;
        private int maxPoi = 0;
        private boolean bothDirections = false;
        private boolean includeRawResponse = false;
        private boolean saveRawToOthers = false;

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
                case "inputFile" -> config.inputFile = value;
                case "contentOutputDir" -> config.contentOutputDir = value;
                case "othersOutputDir" -> config.othersOutputDir = value;
                case "timeoutSeconds" -> config.timeoutSeconds = Integer.parseInt(value);
                case "sleepMillis" -> config.sleepMillis = Long.parseLong(value);
                case "maxPoi" -> config.maxPoi = Integer.parseInt(value);
                case "bothDirections" -> config.bothDirections = Boolean.parseBoolean(value);
                case "includeRawResponse" -> config.includeRawResponse = Boolean.parseBoolean(value);
                case "saveRawToOthers" -> config.saveRawToOthers = Boolean.parseBoolean(value);
                default -> {
                    // ignore unknown option
                }
            }
        }
    }
}
