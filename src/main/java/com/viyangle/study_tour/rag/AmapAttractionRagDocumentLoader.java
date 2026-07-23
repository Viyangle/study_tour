package com.viyangle.study_tour.rag;

import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.mapper.AttractionTagMapper;
import com.viyangle.study_tour.mapper.TagMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.pojo.AttractionTag;
import com.viyangle.study_tour.pojo.Tag;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 高德景点 RAG 文档加载器。
 * 从数据库加载景点数据及其标签，转换为 RAG 文档供向量检索使用。
 */
@Slf4j
@Component
public class AmapAttractionRagDocumentLoader {

    @Autowired
    private AttractionMapper attractionMapper;

    @Autowired
    private AttractionTagMapper attractionTagMapper;

    @Autowired
    private TagMapper tagMapper;

    /**
     * 从数据库加载所有景点，构建 RAG 文档。
     * contentPattern 参数保留以兼容接口，实际从数据库加载。
     *
     * @param contentPattern 未使用，保留兼容
     * @return 文档列表
     */
    public List<Document> loadDocuments(String contentPattern) {
        log.info("开始从数据库加载景点文档...");

        // 1. 加载所有景点
        List<Attraction> attractions = attractionMapper.selectAll();
        if (attractions.isEmpty()) {
            log.warn("数据库中没有景点数据，无法构建 RAG 文档");
            return new ArrayList<>();
        }

        // 2. 预加载标签映射：tagId -> tagName
        List<Tag> allTags = tagMapper.selectAll();
        Map<Long, String> tagNameMap = new HashMap<>();
        for (Tag tag : allTags) {
            tagNameMap.put(tag.getId(), tag.getName());
        }

        // 3. 预加载景点-标签映射：poiId -> List<tagName>
        List<AttractionTag> allAttractionTags = attractionTagMapper.selectAll();
        Map<String, List<String>> poiTagMap = new HashMap<>();
        for (AttractionTag at : allAttractionTags) {
            String tagName = tagNameMap.get(at.getTagId());
            if (tagName != null) {
                poiTagMap.computeIfAbsent(at.getPoiId(), k -> new ArrayList<>()).add(tagName);
            }
        }

        // 4. 构建文档
        List<Document> documents = new ArrayList<>();
        for (Attraction attraction : attractions) {
            if (attraction.getPoiId() == null || attraction.getName() == null) {
                continue;
            }

            List<String> tags = poiTagMap.getOrDefault(attraction.getPoiId(), List.of());
            Document doc = buildDocument(attraction, tags);
            documents.add(doc);
        }

        log.info("景点文档加载完成, 总数={}, 有效文档数={}", attractions.size(), documents.size());
        return documents;
    }

    private Document buildDocument(Attraction a, List<String> tags) {
        // 构建文档文本，包含 poi_id 以便检索端正则提取
        StringBuilder sb = new StringBuilder();
        sb.append("poi_id=").append(a.getPoiId()).append("\n");
        sb.append("名称: ").append(a.getName()).append("\n");
        sb.append("地址: ").append(nullSafe(a.getAddress())).append("\n");
        sb.append("类型: ").append(nullSafe(a.getType())).append("\n");
        sb.append("省: ").append(nullSafe(a.getPname()));
        sb.append(" 市: ").append(nullSafe(a.getCityname()));
        sb.append(" 区: ").append(nullSafe(a.getAdname())).append("\n");
        sb.append("地区编码: ").append(nullSafe(a.getAdcode())).append("\n");
        if (a.getOpentimeToday() != null) {
            sb.append("今日开放: ").append(a.getOpentimeToday()).append("\n");
        }
        if (a.getOpentimeWeek() != null) {
            sb.append("本周开放: ").append(a.getOpentimeWeek()).append("\n");
        }
        if (a.getTel() != null) {
            sb.append("电话: ").append(a.getTel()).append("\n");
        }
        if (!tags.isEmpty()) {
            sb.append("标签: ").append(String.join(", ", tags)).append("\n");
        }

        String text = sb.toString().trim();

        // metadata 中设置 poi_id，与 VectorCandidateRetrieverServiceImpl 的提取逻辑对齐
        Metadata metadata = new Metadata()
                .put("poi_id", a.getPoiId())
                .put("name", a.getName())
                .put("adcode", nullSafe(a.getAdcode()))
                .put("type", nullSafe(a.getType()));

        return Document.from(text, metadata);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
