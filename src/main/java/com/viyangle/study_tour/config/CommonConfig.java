package com.viyangle.study_tour.config;

import com.viyangle.study_tour.rag.AmapAttractionRagDocumentLoader;
import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Slf4j
@Configuration
public class CommonConfig {
    @Autowired
    private EmbeddingModel embeddingModel;
    @Autowired
    private ChatMemoryStore redisChatMemoryStore;
    @Autowired(required = false)
    private RedisEmbeddingStore redisEmbeddingStore;

    /**
     * Redis Stack (RediSearch) 向量库 Bean。
     * 默认关闭，避免本地没有 Redis Stack 时启动失败；生产开启
     * app.rag.embedding.store-enabled=true 后，新增景点会同时写入 MySQL 和向量索引。
     */
    @Bean
    @ConditionalOnProperty(name = "app.rag.embedding.store-enabled", havingValue = "true")
    public RedisEmbeddingStore redisEmbeddingStore(
            @Value("${langchain4j.community.redis.host:localhost}") String host,
            @Value("${langchain4j.community.redis.port:6379}") int port,
            @Value("${langchain4j.community.redis.index-name:study-tour-embedding-index}") String indexName,
            @Value("${langchain4j.community.redis.prefix:study-tour:embedding:}") String prefix,
            @Value("${langchain4j.community.redis.dimension:1536}") int dimension,
            @Value("${langchain4j.community.redis.metadata-keys:poi_id,name,adcode,type}") List<String> metadataKeys) {
        return RedisEmbeddingStore.builder()
                .host(host)
                .port(port)
                .indexName(indexName)
                .prefix(prefix)
                .dimension(dimension)
                .metadataKeys(metadataKeys)
                .build();
    }

    @Autowired
    private AmapAttractionRagDocumentLoader amapAttractionRagDocumentLoader;

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        return memoryId -> MessageWindowChatMemory.builder()
                .maxMessages(20)
                .id(memoryId)
                .chatMemoryStore(redisChatMemoryStore)
                .build();
    }

    @Bean
    public ApplicationRunner embeddingIngestRunner(
            @Value("${app.rag.embedding.ingest-enabled:false}") boolean ingestEnabled,
            @Value("${app.rag.embedding.clear-before-ingest:false}") boolean clearBeforeIngest,
            @Value("${app.rag.embedding.content-pattern:classpath*:content/*.json}") String contentPattern,
            @Value("${app.rag.embedding.splitter-enabled:true}") boolean splitterEnabled,
            @Value("${app.rag.embedding.splitter-max-segment-size:200}") int maxSegmentSize,
            @Value("${app.rag.embedding.splitter-max-overlap-size:20}") int maxOverlapSize) {
        return args -> {
            if (redisEmbeddingStore == null) {
                log.warn("RedisEmbeddingStore not available (Redis Stack required for vector search), skipping ingest");
                return;
            }
            if (!ingestEnabled) {
                log.info("RAG embedding ingest skipped, app.rag.embedding.ingest-enabled=false");
                return;
            }

            if (clearBeforeIngest) {
                log.info("Clearing Redis embedding store before ingest");
                redisEmbeddingStore.removeAll();
            }

            List<Document> documents = amapAttractionRagDocumentLoader.loadDocuments(contentPattern);
            if (documents.isEmpty()) {
                log.warn("No RAG documents loaded from pattern: {}", contentPattern);
                return;
            }

            EmbeddingStoreIngestor.Builder ingestorBuilder = EmbeddingStoreIngestor.builder()
                    .embeddingStore(redisEmbeddingStore)
                    .embeddingModel(embeddingModel);

            if (splitterEnabled) {
                DocumentSplitter splitter = DocumentSplitters.recursive(maxSegmentSize, maxOverlapSize);
                ingestorBuilder.documentSplitter(splitter);
            }

            ingestorBuilder.build().ingest(documents);
            log.info("RAG embedding ingest completed, documentCount={}, splitterEnabled={}, contentPattern={}",
                    documents.size(), splitterEnabled, contentPattern);
        };
    }

    @Bean
    public ContentRetriever contentRetriever(
            @Value("${app.rag.embedding.min-score:0.4}") double minScore,
            @Value("${app.rag.embedding.max-results:30}") int maxResults) {
        if (redisEmbeddingStore == null) {
            log.warn("RedisEmbeddingStore not available, ContentRetriever will return empty results. " +
                     "Vector search disabled, using Knowledge Graph for candidate retrieval.");
            return query -> List.of();
        }
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(redisEmbeddingStore)
                .minScore(minScore)
                .maxResults(maxResults)
                .embeddingModel(embeddingModel)
                .build();
    }
}
