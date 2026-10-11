package com.viyangle.study_tour.service.impl;

import com.viyangle.study_tour.mapper.AttractionMapper;
import com.viyangle.study_tour.pojo.Attraction;
import com.viyangle.study_tour.rag.AmapAttractionRagDocumentLoader;
import com.viyangle.study_tour.service.AttractionSyncService;
import com.viyangle.study_tour.utils.AmapTransitClient;
import com.viyangle.study_tour.utils.SecurityContextUtil;
import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.exception.UnauthorizedException;
import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class AttractionSyncServiceImpl implements AttractionSyncService {

    @Autowired
    private AmapTransitClient amapTransitClient;

    @Autowired
    private AttractionMapper attractionMapper;

    @Autowired
    private AmapAttractionRagDocumentLoader amapAttractionRagDocumentLoader;

    @Autowired(required = false)
    private RedisEmbeddingStore redisEmbeddingStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Value("${app.rag.embedding.sync-vector-enabled:true}")
    private boolean syncVectorEnabled;

    @Value("${app.rag.embedding.splitter-enabled:true}")
    private boolean splitterEnabled;

    @Value("${app.rag.embedding.splitter-max-segment-size:200}")
    private int maxSegmentSize;

    @Value("${app.rag.embedding.splitter-max-overlap-size:20}")
    private int maxOverlapSize;

    @Override
    public Attraction syncFromAmap(String poiId) {
        if (poiId == null || poiId.isBlank()) {
            throw new IllegalArgumentException("poiId is required");
        }
        String normalizedPoiId = poiId.trim();

        Attraction attraction = amapTransitClient.fetchAttraction(normalizedPoiId);
        if (attraction == null || attraction.getPoiId() == null || attraction.getPoiId().isBlank()) {
            throw new IllegalArgumentException("AMap place/detail 未返回有效景点: " + normalizedPoiId);
        }

        attraction.setPoiId(normalizedPoiId);
        attraction.setStatus("ACTIVE");
        attractionMapper.upsert(attraction);
        log.info("Attraction synced to MySQL, poiId={}, name={}", normalizedPoiId, attraction.getName());

        indexAttraction(attraction);
        return attraction;
    }

    @Override
    public int reindexAll() {
        if (SecurityContextUtil.currentAccountId() == null) {
            throw new UnauthorizedException("未认证用户");
        }
        if (!"ADMIN".equalsIgnoreCase(SecurityContextUtil.currentRole())) {
            throw new ForbiddenException("仅管理员可重建向量索引");
        }
        if (redisEmbeddingStore == null) {
            log.warn("Redis vector reindex skipped: RedisEmbeddingStore 不可用（请开启 app.rag.embedding.store-enabled=true 且保证 Redis Stack 可用）");
            return 0;
        }
        List<Document> documents = amapAttractionRagDocumentLoader.loadDocuments(null);
        if (documents.isEmpty()) {
            log.warn("Redis vector reindex skipped: 没有可写入的景点文档");
            return 0;
        }
        try {
            redisEmbeddingStore.removeAll();
            buildIngestor().ingest(documents);
            log.info("Redis vector reindex completed, documentCount={}", documents.size());
            return documents.size();
        } catch (Exception e) {
            log.error("Redis vector reindex failed", e);
            throw new IllegalStateException("Redis vector reindex failed", e);
        }
    }

    private void indexAttraction(Attraction attraction) {
        if (!syncVectorEnabled) {
            log.info("Vector index update skipped for poiId={}, app.rag.embedding.sync-vector-enabled=false",
                    attraction.getPoiId());
            return;
        }
        if (redisEmbeddingStore == null) {
            log.warn("Vector index update skipped for poiId={}: RedisEmbeddingStore 不可用（请开启 app.rag.embedding.store-enabled=true）",
                    attraction.getPoiId());
            return;
        }
        try {
            Document document = amapAttractionRagDocumentLoader.buildDocument(attraction);
            if (document == null) {
                log.warn("Vector index update skipped for poiId={}: 景点文档为空", attraction.getPoiId());
                return;
            }
            // 先删除该 poiId 的旧向量，再写入，保证幂等、不产生重复片段
            redisEmbeddingStore.removeAll(
                    MetadataFilterBuilder.metadataKey("poi_id").isEqualTo(attraction.getPoiId()));
            buildIngestor().ingest(List.of(document));
            log.info("Vector index updated for poiId={}", attraction.getPoiId());
        } catch (Exception e) {
            // MySQL 已经更新成功，向量索引失败只记录日志，不阻断主流程
            log.error("Vector index update failed for poiId={}, MySQL 已更新: {}",
                    attraction.getPoiId(), e.getMessage());
        }
    }

    private EmbeddingStoreIngestor buildIngestor() {
        EmbeddingStoreIngestor.Builder builder = EmbeddingStoreIngestor.builder()
                .embeddingStore(redisEmbeddingStore)
                .embeddingModel(embeddingModel);
        if (splitterEnabled) {
            DocumentSplitter splitter = DocumentSplitters.recursive(maxSegmentSize, maxOverlapSize);
            builder.documentSplitter(splitter);
        }
        return builder.build();
    }
}
