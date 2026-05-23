package com.yunzhu.video_data_analysis.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import redis.clients.jedis.JedisPooled;

/**
 * 创建 Redis 向量存储 Bean。
 * 替换了之前基于 Milvus 的实现，减少部署依赖。
 * <p>
 * 需要显式声明 metadata 字段，否则 RediSearch 索引无法对这些字段做过滤。
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel,
                                   @Value("${spring.data.redis.host:localhost}") String redisHost,
                                   @Value("${spring.data.redis.port:6379}") int redisPort,
                                   @Value("${spring.ai.vectorstore.redis.index:video_analysis_idx}") String index,
                                   @Value("${spring.ai.vectorstore.redis.prefix:vector:}") String prefix) {
        JedisPooled jedis = new JedisPooled(redisHost, redisPort);
        return RedisVectorStore.builder(jedis, embeddingModel)
                .indexName(index)
                .prefix(prefix)
                .initializeSchema(true)
                // 必须在建索引时声明 metadata 字段，否则 filter 表达式不生效
                .metadataFields(
                        RedisVectorStore.MetadataField.tag("doc_type"),
                        RedisVectorStore.MetadataField.tag("contentId"),
                        RedisVectorStore.MetadataField.tag("sentiment"),
                        RedisVectorStore.MetadataField.text("response"))
                .build();
    }
}
