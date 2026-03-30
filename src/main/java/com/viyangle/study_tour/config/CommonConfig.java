package com.viyangle.study_tour.config;

import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class CommonConfig {
    @Autowired
    private EmbeddingModel embeddingModel;
    @Autowired
    private ChatMemoryStore redisChatMemoryStore;
    @Autowired
    private RedisEmbeddingStore redisEmbeddingStore;

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
            @Value("${app.rag.embedding.splitter-enabled:true}") boolean splitterEnabled,
            @Value("${app.rag.embedding.splitter-max-segment-size:200}") int maxSegmentSize,
            @Value("${app.rag.embedding.splitter-max-overlap-size:20}") int maxOverlapSize) {
        return args -> {
            if (!ingestEnabled) {
                return;
            }

            List<Document> documents = ClassPathDocumentLoader.loadDocuments("content");
            EmbeddingStoreIngestor.Builder ingestorBuilder = EmbeddingStoreIngestor.builder()
                    .embeddingStore(redisEmbeddingStore)
                    .embeddingModel(embeddingModel);

            if (splitterEnabled) {
                DocumentSplitter splitter = DocumentSplitters.recursive(maxSegmentSize, maxOverlapSize);
                ingestorBuilder.documentSplitter(splitter);
            }

            ingestorBuilder.build().ingest(documents);
        };
    }

    @Bean
    public ContentRetriever contentRetriever() {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(redisEmbeddingStore)
                .minScore(0.5)
                .maxResults(12)
                .embeddingModel(embeddingModel)
                .build();
    }
}
