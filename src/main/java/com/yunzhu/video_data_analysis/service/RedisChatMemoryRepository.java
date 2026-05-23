package com.yunzhu.video_data_analysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于Redis的 {@link ChatMemoryRepository}，用于持久化对话历史。
 * <p>
 * 消息被序列化为JSON，包括类型信息，以便
 * 多态消息类型（UserMessage、AssistantMessage等）
 * 能够正确地在反序列化后保留。
 * <p>
 * 每个对话有24小时TTL用于自动清理。
 */
@Component
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final String KEY_PREFIX = "chat:memory:";
    private static final long TTL_HOURS = 24;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatMemoryRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;

        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("org.springframework.ai.chat.messages")
                .allowIfSubType("java.util")
                .allowIfSubType("com.fasterxml.jackson")
                .build();

        this.objectMapper = new ObjectMapper()
                .activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL);
    }

    @Override
    public List<String> findConversationIds() {
        // 模式扫描开销大；生产环境返回空。
        // 对话ID在调用时通过 MessageChatMemoryAdvisor 已知。
        return Collections.emptyList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String json = redisTemplate.opsForValue().get(key(conversationId));
        if (json == null) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Message>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        try {
            String json = objectMapper.writeValueAsString(messages);
            redisTemplate.opsForValue().set(key(conversationId), json, TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist chat memory to Redis", e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redisTemplate.delete(key(conversationId));
    }

    private static String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }
}
