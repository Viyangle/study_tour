package com.viyangle.study_tour.rag;

import dev.langchain4j.data.document.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 高德景点 RAG 文档加载器。
 * 从 classpath 下的 JSON 文件中加载景点数据，转换为 RAG 文档。
 */
@Slf4j
@Component
public class AmapAttractionRagDocumentLoader {

    /**
     * 从指定路径模式加载文档。
     *
     * @param contentPattern 资源路径模式，如 "classpath*:content/*.json"
     * @return 文档列表
     */
    public List<Document> loadDocuments(String contentPattern) {
        // TODO: 实现从 JSON 文件加载景点数据并转换为 Document 的逻辑
        log.warn("AmapAttractionRagDocumentLoader.loadDocuments 尚未实现, pattern={}", contentPattern);
        return new ArrayList<>();
    }
}
