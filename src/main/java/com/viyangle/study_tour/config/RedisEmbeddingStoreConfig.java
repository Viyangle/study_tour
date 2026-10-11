package com.viyangle.study_tour.config;

import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Redis Stack (RediSearch) 向量库配置。
 * 独立于 CommonConfig，避免同一配置类内字段注入自己 @Bean 方法产物造成循环依赖。
 * 默认关闭，避免本地没有 Redis Stack 时启动失败；生产开启
 * app.rag.embedding.store-enabled=true 后，新增景点会同时写入 MySQL 和向量索引。
 */
@Configuration
public class RedisEmbeddingStoreConfig {

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
}
