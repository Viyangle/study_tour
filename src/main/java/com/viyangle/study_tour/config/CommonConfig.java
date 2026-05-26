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
    @Autowired
    private RedisEmbeddingStore redisEmbeddingStore;
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
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(redisEmbeddingStore)
                .minScore(minScore)
                .maxResults(maxResults)
                .embeddingModel(embeddingModel)
                .build();
    }
}
