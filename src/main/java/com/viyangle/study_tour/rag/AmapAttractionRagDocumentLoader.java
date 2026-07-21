package com.viyangle.study_tour.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public class AmapAttractionRagDocumentLoader {

    private static final String DEFAULT_CONTENT_PATTERN = "classpath*:content/*.json";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StudyTourTagClassifier tagClassifier;

    private final ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();

    public List<Document> loadDocuments() throws IOException {
        return loadDocuments(DEFAULT_CONTENT_PATTERN);
    }

    public List<Document> loadDocuments(String contentPattern) throws IOException {
        String pattern = contentPattern == null || contentPattern.isBlank()
                ? DEFAULT_CONTENT_PATTERN
                : contentPattern.trim();
        Resource[] resources = resourcePatternResolver.getResources(pattern);
        List<Document> documents = new ArrayList<>();
        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                continue;
            }
            try (InputStream in = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(in);
                addDocuments(root, resource.getFilename(), documents);
            }
        }
        return documents;
    }

    private void addDocuments(JsonNode root, String sourceFile, List<Document> output) {
        if (root == null || root.isNull()) {
            return;
        }
        if (root.isArray()) {
            for (JsonNode node : root) {
                addAttractionDocument(node, sourceFile, output);
            }
            return;
        }
        if (root.path("pois").isArray()) {
            for (JsonNode node : root.path("pois")) {
                addAttractionDocument(node, sourceFile, output);
            }
            return;
        }
        if (root.path("data").isArray()) {
            for (JsonNode node : root.path("data")) {
                addAttractionDocument(node, sourceFile, output);
            }
            return;
        }
        if (root.path("docs").isArray()) {
            for (JsonNode node : root.path("docs")) {
                addPreparedDocument(node, sourceFile, output);
            }
        }
    }

    private void addAttractionDocument(JsonNode node, String sourceFile, List<Document> output) {
        if (node == null || node.isNull()) {
            return;
        }

        String poiId = firstText(node, "poi_id", "poiId", "id");
        String name = text(node, "name");
        if (poiId.isBlank() || name.isBlank()) {
            return;
        }

        String address = text(node, "address");
        String location = text(node, "location");
        String pcode = text(node, "pcode");
        String pname = text(node, "pname");
        String citycode = text(node, "citycode");
        String cityname = text(node, "cityname");
        String adcode = text(node, "adcode");
        String adname = text(node, "adname");
        String type = text(node, "type");
        String typecode = text(node, "typecode");
        String distance = text(node, "distance");

        JsonNode business = node.path("business");
        String rating = text(business, "rating");
        String tel = text(business, "tel");
        String opentimeToday = text(business, "opentime_today");
        String opentimeWeek = text(business, "opentime_week");
        String keytag = text(business, "keytag");
        String rectag = text(business, "rectag");

        List<String> tags = tagClassifier.classify(name, address, cityname, adname, type, typecode,
                keytag, rectag);
        String tag = tagClassifier.primaryTag(tags);
        String tagsText = String.join(",", tags);

        Metadata metadata = new Metadata()
                .put("doc_type", "attraction")
                .put("poi_id", poiId)
                .put("poiId", poiId)
                .put("name", name)
                .put("tag", tag)
                .put("tags", tagsText)
                .put("citycode", citycode)
                .put("cityname", cityname)
                .put("adcode", adcode)
                .put("adname", adname)
                .put("typecode", typecode)
                .put("source_file", sourceFile == null ? "" : sourceFile);

        String ragText = buildAttractionText(name, poiId, tag, tagsText, cityname, citycode, adname,
                adcode, address, location, type, typecode, rating, tel, opentimeToday, opentimeWeek,
                keytag, rectag, pname, pcode, distance);
        output.add(Document.from(ragText, metadata));
    }

    private void addPreparedDocument(JsonNode node, String sourceFile, List<Document> output) {
        String ragText = text(node, "rag_text");
        if (ragText.isBlank()) {
            return;
        }
        Metadata metadata = new Metadata();
        JsonNode metadataNode = node.path("metadata");
        if (metadataNode.isObject()) {
            metadataNode.fields().forEachRemaining(entry -> {
                if (entry.getValue() != null && !entry.getValue().isNull()) {
                    metadata.put(entry.getKey(), entry.getValue().asText(""));
                }
            });
        }
        putIfMissing(metadata, "doc_type", text(node, "doc_type"));
        putIfMissing(metadata, "source_file", sourceFile == null ? "" : sourceFile);
        output.add(Document.from(ragText, metadata));
    }

    private String buildAttractionText(String name,
                                       String poiId,
                                       String tag,
                                       String tagsText,
                                       String cityname,
                                       String citycode,
                                       String adname,
                                       String adcode,
                                       String address,
                                       String location,
                                       String type,
                                       String typecode,
                                       String rating,
                                       String tel,
                                       String opentimeToday,
                                       String opentimeWeek,
                                       String keytag,
                                       String rectag,
                                       String pname,
                                       String pcode,
                                       String distance) {
        StringBuilder sb = new StringBuilder();
        appendSentence(sb, "景点", name + "（POI=" + poiId + "）");
        appendSentence(sb, "主研学标签", tag);
        appendSentence(sb, "研学标签", tagsText);
        appendRegion(sb, pname, pcode, cityname, citycode, adname, adcode);
        appendSentence(sb, "地址", address);
        appendSentence(sb, "坐标", location);
        appendSentence(sb, "高德类型", type);
        appendSentence(sb, "类型编码", typecode);
        appendSentence(sb, "评分", rating);
        appendSentence(sb, "今日开放时间", opentimeToday);
        appendSentence(sb, "周开放时间", opentimeWeek);
        appendSentence(sb, "联系电话", tel);
        appendSentence(sb, "高德标签", keytag);
        appendSentence(sb, "推荐语", rectag);
        appendSentence(sb, "距离", distance);
        return sb.toString();
    }

    private void appendRegion(StringBuilder sb,
                              String pname,
                              String pcode,
                              String cityname,
                              String citycode,
                              String adname,
                              String adcode) {
        List<String> parts = new ArrayList<>();
        addPart(parts, "省份", pname, pcode);
        addPart(parts, "城市", cityname, citycode);
        addPart(parts, "区县", adname, adcode);
        if (!parts.isEmpty()) {
            sb.append(String.join("，", parts)).append("。");
        }
    }

    private void addPart(List<String> parts, String label, String name, String code) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (code == null || code.isBlank()) {
            parts.add(label + "：" + name);
        } else {
            parts.add(label + "：" + name + "（编码：" + code + "）");
        }
    }

    private void appendSentence(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(label).append("：").append(value.trim()).append("。");
    }

    private void putIfMissing(Metadata metadata, String key, String value) {
        if (metadata == null || key == null || key.isBlank() || metadata.containsKey(key)) {
            return;
        }
        metadata.put(key, value == null ? "" : value);
    }

    private String firstText(JsonNode node, String... fields) {
        if (fields == null || fields.length == 0) {
            return "";
        }
        for (String field : fields) {
            String value = text(node, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isNull() || field == null) {
            return "";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("").trim();
    }
}
