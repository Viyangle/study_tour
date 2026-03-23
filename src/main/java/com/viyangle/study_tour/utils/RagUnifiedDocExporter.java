package com.viyangle.study_tour.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Build RAG-friendly unified docs from attractions + transit routes.
 *
 * Example:
 * mvn -DskipTests exec:java -Dexec.mainClass="com.viyangle.study_tour.utils.RagUnifiedDocExporter" -Dexec.args="--attractionsFile=src/main/resources/content/attractions_merged_20260323_170243.json --routesFile=src/main/resources/content/transit_routes_20260323_194405.json"
 */
public class RagUnifiedDocExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter TS_FILE = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter TS_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        Config config = Config.fromArgs(args);
        validate(config);

        Path attractionsFile = Paths.get(config.attractionsFile);
        Path routesFile = Paths.get(config.routesFile);
        Path outputDir = Paths.get(config.outputDir);
        Files.createDirectories(outputDir);

        JsonNode attractionsRoot = MAPPER.readTree(Files.readString(attractionsFile, StandardCharsets.UTF_8));
        JsonNode routesRoot = MAPPER.readTree(Files.readString(routesFile, StandardCharsets.UTF_8));

        Map<String, String> poiNameMap = new HashMap<>();
        ArrayNode docs = MAPPER.createArrayNode();

        int attractionCount = buildAttractionDocs(attractionsRoot, docs, poiNameMap);
        int[] routeStats = buildRouteDocsWithReverse(routesRoot, docs, poiNameMap, config.autoReverseRoute);

        ObjectNode output = MAPPER.createObjectNode();
        output.put("generated_at", LocalDateTime.now().format(TS_TEXT));
        output.put("attractions_file", attractionsFile.toAbsolutePath().toString());
        output.put("routes_file", routesFile.toAbsolutePath().toString());
        output.put("auto_reverse_route", config.autoReverseRoute);
        output.put("attraction_docs", attractionCount);
        output.put("route_docs_original", routeStats[0]);
        output.put("route_docs_added_reverse", routeStats[1]);
        output.put("total_docs", docs.size());
        output.set("docs", docs);

        String fileName = "rag_unified_docs_" + LocalDateTime.now().format(TS_FILE) + ".json";
        Path out = outputDir.resolve(fileName);
        MAPPER.writeValue(out.toFile(), output);

        if (config.writeLatestCopy) {
            Path latest = outputDir.resolve("rag_unified_docs_latest.json");
            MAPPER.writeValue(latest.toFile(), output);
        }

        System.out.printf("Done: attractionDocs=%d, routeOriginal=%d, routeAddedReverse=%d, total=%d, output=%s%n",
                attractionCount, routeStats[0], routeStats[1], docs.size(), out.toAbsolutePath());
    }

    private static void validate(Config config) {
        if (!Files.exists(Paths.get(config.attractionsFile))) {
            throw new IllegalArgumentException("Attractions file not found: " + config.attractionsFile);
        }
        if (!Files.exists(Paths.get(config.routesFile))) {
            throw new IllegalArgumentException("Routes file not found: " + config.routesFile);
        }
    }

    private static int buildAttractionDocs(JsonNode root, ArrayNode docs, Map<String, String> poiNameMap) {
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("Attractions JSON must be array.");
        }
        int count = 0;
        for (JsonNode node : root) {
            String poiId = text(node, "poi_id");
            if (poiId.isBlank()) {
                continue;
            }

            String name = text(node, "name");
            String address = text(node, "address");
            String location = text(node, "location");
            String city = text(node, "cityname");
            String cityCode = text(node, "citycode");
            String adname = text(node, "adname");
            String type = text(node, "type");
            String typecode = text(node, "typecode");

            JsonNode business = node.path("business");
            String rating = text(business, "rating");
            String tel = text(business, "tel");
            String openToday = text(business, "opentime_today");
            String openWeek = text(business, "opentime_week");
            String keytag = text(business, "keytag");
            String rectag = text(business, "rectag");

            poiNameMap.put(poiId, name);

            ObjectNode doc = MAPPER.createObjectNode();
            doc.put("doc_id", "attraction:" + poiId);
            doc.put("doc_type", "attraction");
            doc.put("title", name.isBlank() ? poiId : name);
            doc.put("rag_text", buildAttractionText(name, poiId, city, cityCode, adname, address, location, type, typecode,
                    rating, tel, openToday, openWeek, keytag, rectag));
            doc.set("metadata", buildAttractionMeta(poiId, name, city, cityCode, adname, typecode));
            doc.set("source", node);
            docs.add(doc);
            count++;
        }
        return count;
    }

    private static int[] buildRouteDocsWithReverse(JsonNode routesRoot, ArrayNode docs, Map<String, String> poiNameMap, boolean autoReverse) {
        JsonNode routes = routesRoot.path("routes");
        if (!routes.isArray()) {
            throw new IllegalArgumentException("Routes JSON must contain array field: routes.");
        }

        int originalCount = 0;
        int reverseCount = 0;
        Set<String> seenDirection = new HashSet<>();
        Set<String> originalDirection = new HashSet<>();
        ArrayNode originals = MAPPER.createArrayNode();

        for (JsonNode route : routes) {
            String fromPoi = text(route, "from_poi_id");
            String toPoi = text(route, "to_poi_id");
            if (fromPoi.isBlank() || toPoi.isBlank()) {
                continue;
            }
            originalDirection.add(dirKey(fromPoi, toPoi));
            originals.add(route);
        }

        for (JsonNode route : originals) {
            String fromPoi = text(route, "from_poi_id");
            String toPoi = text(route, "to_poi_id");
            String direction = dirKey(fromPoi, toPoi);
            if (seenDirection.add(direction)) {
                docs.add(buildRouteDoc(route, poiNameMap, false));
                originalCount++;
            }
        }

        if (autoReverse) {
            for (JsonNode route : originals) {
                String fromPoi = text(route, "from_poi_id");
                String toPoi = text(route, "to_poi_id");
                String reverseDirection = dirKey(toPoi, fromPoi);
                if (originalDirection.contains(reverseDirection)) {
                    continue;
                }
                if (!seenDirection.add(reverseDirection)) {
                    continue;
                }
                docs.add(buildRouteDoc(route, poiNameMap, true));
                reverseCount++;
            }
        }

        return new int[]{originalCount, reverseCount};
    }

    private static ObjectNode buildRouteDoc(JsonNode route, Map<String, String> poiNameMap, boolean reversed) {
        String fromPoi = text(route, "from_poi_id");
        String fromName = text(route, "from_name");
        String toPoi = text(route, "to_poi_id");
        String toName = text(route, "to_name");
        String city1 = text(route, "city1");
        String city2 = text(route, "city2");

        if (reversed) {
            String tPoi = fromPoi;
            fromPoi = toPoi;
            toPoi = tPoi;

            String tName = fromName;
            fromName = toName;
            toName = tName;

            String tCity = city1;
            city1 = city2;
            city2 = tCity;
        }

        if (fromName.isBlank()) {
            fromName = poiNameMap.getOrDefault(fromPoi, fromPoi);
        }
        if (toName.isBlank()) {
            toName = poiNameMap.getOrDefault(toPoi, toPoi);
        }

        int routeDistance = intValue(route, "route_distance_m");
        int bestTransitDistance = intValue(route, "best_transit_distance_m");
        int bestWalkingDistance = intValue(route, "best_walking_distance_m");
        int transitCount = intValue(route, "transit_count");
        String nightFlag = text(route, "best_nightflag");

        ArrayNode lineNames = MAPPER.createArrayNode();
        JsonNode bestLines = route.path("best_lines");
        if (bestLines.isArray()) {
            for (JsonNode line : bestLines) {
                if (line != null && !line.asText("").isBlank()) {
                    lineNames.add(line.asText(""));
                }
            }
        }

        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("doc_id", "route:" + fromPoi + "->" + toPoi);
        doc.put("doc_type", "route");
        doc.put("title", fromName + " -> " + toName);
        doc.put("rag_text", buildRouteText(fromName, fromPoi, toName, toPoi, city1, city2, routeDistance,
                transitCount, bestTransitDistance, bestWalkingDistance, nightFlag, lineNames, reversed));
        doc.set("metadata", buildRouteMeta(fromPoi, fromName, toPoi, toName, city1, city2, reversed));

        ObjectNode source = route.deepCopy();
        if (reversed) {
            source.put("from_poi_id", fromPoi);
            source.put("from_name", fromName);
            source.put("to_poi_id", toPoi);
            source.put("to_name", toName);
            source.put("city1", city1);
            source.put("city2", city2);
            source.put("is_reverse_synthesized", true);
        } else {
            source.put("is_reverse_synthesized", false);
        }
        doc.set("source", source);
        return doc;
    }

    private static ObjectNode buildAttractionMeta(String poiId, String name, String city, String cityCode, String district, String typeCode) {
        ObjectNode meta = MAPPER.createObjectNode();
        meta.put("poi_id", poiId);
        meta.put("name", name);
        meta.put("city", city);
        meta.put("city_code", cityCode);
        meta.put("district", district);
        meta.put("type_code", typeCode);
        return meta;
    }

    private static ObjectNode buildRouteMeta(String fromPoi, String fromName, String toPoi, String toName,
                                             String city1, String city2, boolean reversed) {
        ObjectNode meta = MAPPER.createObjectNode();
        meta.put("from_poi_id", fromPoi);
        meta.put("from_name", fromName);
        meta.put("to_poi_id", toPoi);
        meta.put("to_name", toName);
        meta.put("city1", city1);
        meta.put("city2", city2);
        meta.put("is_reverse_synthesized", reversed);
        meta.put("pair_key", fromPoi + "|" + toPoi);
        return meta;
    }

    private static String buildAttractionText(String name, String poiId, String city, String cityCode, String district,
                                              String address, String location, String type, String typeCode,
                                              String rating, String tel, String openToday, String openWeek,
                                              String keytag, String rectag) {
        StringBuilder sb = new StringBuilder();
        sb.append("景点：").append(nvl(name, poiId)).append("（POI=").append(poiId).append("）。");
        if (!city.isBlank() || !district.isBlank()) {
            sb.append("城市：").append(city);
            if (!district.isBlank()) {
                sb.append("，区县：").append(district);
            }
            if (!cityCode.isBlank()) {
                sb.append("，城市编码：").append(cityCode);
            }
            sb.append("。");
        }
        if (!address.isBlank()) {
            sb.append("地址：").append(address).append("。");
        }
        if (!location.isBlank()) {
            sb.append("坐标：").append(location).append("。");
        }
        if (!type.isBlank()) {
            sb.append("类型：").append(type);
            if (!typeCode.isBlank()) {
                sb.append("（typecode=").append(typeCode).append("）");
            }
            sb.append("。");
        }
        if (!rating.isBlank()) {
            sb.append("评分：").append(rating).append("。");
        }
        if (!openToday.isBlank()) {
            sb.append("今日开放时间：").append(openToday).append("。");
        }
        if (!openWeek.isBlank()) {
            sb.append("周开放时间：").append(openWeek).append("。");
        }
        if (!tel.isBlank()) {
            sb.append("联系电话：").append(tel).append("。");
        }
        if (!keytag.isBlank()) {
            sb.append("标签：").append(keytag).append("。");
        }
        if (!rectag.isBlank()) {
            sb.append("推荐语：").append(rectag).append("。");
        }
        return sb.toString();
    }

    private static String buildRouteText(String fromName, String fromPoi, String toName, String toPoi,
                                         String city1, String city2, int routeDistance, int transitCount,
                                         int bestTransitDistance, int bestWalkingDistance,
                                         String nightFlag, ArrayNode lineNames, boolean reversed) {
        StringBuilder sb = new StringBuilder();
        sb.append("通勤路径：从").append(nvl(fromName, fromPoi)).append("（POI=").append(fromPoi).append("）")
                .append("到").append(nvl(toName, toPoi)).append("（POI=").append(toPoi).append("）。");
        if (!city1.isBlank() || !city2.isBlank()) {
            sb.append("城市编码：").append(city1).append(" -> ").append(city2).append("。");
        }
        if (routeDistance >= 0) {
            sb.append("路径总距离约").append(routeDistance).append("米。");
        }
        if (transitCount >= 0) {
            sb.append("候选公共交通方案数：").append(transitCount).append("。");
        }
        if (bestTransitDistance >= 0) {
            sb.append("推荐方案公交/地铁里程约").append(bestTransitDistance).append("米。");
        }
        if (bestWalkingDistance >= 0) {
            sb.append("推荐方案步行约").append(bestWalkingDistance).append("米。");
        }
        if (!nightFlag.isBlank()) {
            sb.append("夜间标识：").append("1".equals(nightFlag) ? "夜间" : "非夜间").append("。");
        }
        if (lineNames != null && lineNames.size() > 0) {
            sb.append("推荐线路：");
            for (int i = 0; i < lineNames.size(); i++) {
                if (i > 0) {
                    sb.append("，");
                }
                sb.append(lineNames.get(i).asText(""));
            }
            sb.append("。");
        }
        if (reversed) {
            sb.append("该记录为根据正向通勤数据自动补齐的反向路径。");
        }
        return sb.toString();
    }

    private static String dirKey(String fromPoi, String toPoi) {
        return fromPoi + "->" + toPoi;
    }

    private static int intValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return -1;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        String text = value.asText("").trim();
        if (text.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(text);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return "";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("").trim();
    }

    private static String nvl(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private static class Config {
        private String attractionsFile = "src/main/resources/content/attractions_merged_20260323_170243.json";
        private String routesFile = "src/main/resources/content/transit_routes_20260323_194405.json";
        private String outputDir = "src/main/resources/content";
        private boolean autoReverseRoute = true;
        private boolean writeLatestCopy = true;

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
            return config;
        }

        private static void apply(Config config, String key, String value) {
            switch (key) {
                case "attractionsFile" -> config.attractionsFile = value;
                case "routesFile" -> config.routesFile = value;
                case "outputDir" -> config.outputDir = value;
                case "autoReverseRoute" -> config.autoReverseRoute = Boolean.parseBoolean(value);
                case "writeLatestCopy" -> config.writeLatestCopy = Boolean.parseBoolean(value);
                default -> {
                    // ignore unknown options
                }
            }
        }
    }
}
