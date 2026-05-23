package com.yunzhu.video_data_analysis.config;

import com.yunzhu.video_data_analysis.service.RedisChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 聊天记忆配置，使用<b>Redis持久化</b>。
 * <p>
 * 记忆管理：{@link MessageWindowChatMemory}（滑动窗口，最大20条）。
 * 存储后端：{@link RedisChatMemoryRepository}（JSON序列化，24小时TTL）。
 * <p>
 * 这种组合提供：
 * <ul>
 *   <li><b>短期窗口</b> — 每个用户只保留最后20条消息</li>
 *   <li><b>长期持久化</b> — 应用重启后依然存在，支持水平扩展</li>
 *   <li><b>自动清理</b> — 24小时Redis TTL防止存储无限增长</li>
 * </ul>
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(RedisChatMemoryRepository redisRepo) {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .chatMemoryRepository(redisRepo)
                .build();
    }
}
