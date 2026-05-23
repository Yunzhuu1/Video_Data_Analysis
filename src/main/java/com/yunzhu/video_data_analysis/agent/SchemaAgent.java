package com.yunzhu.video_data_analysis.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 识别用户问题相关的数据库表和字段，
 * 返回一个<b>裁剪后的schema</b>，其中只包含必要的表、
 * 字段和关联路径。
 * <p>
 * 裁剪后的schema由 {@link SQLGenerationAgent} 消费，后者不再
 * 在其系统提示词中携带完整的schema。这使得SchemaAgent的
 * 输出真正有价值 — 它缩小了SQLAgent的关注范围并减少了
 * 提示词大小。
 * <p>
 * 使用廉价模型 (gpt-4o-mini)：schema裁剪是类似检索的任务
 * （将问题关键词匹配到表/字段名），而不是深度推理任务。
 */
@Component
public class SchemaAgent {

    private static final Logger log = LoggerFactory.getLogger(SchemaAgent.class);

    private static final String SYSTEM_PROMPT = """
            裁剪Schema。只输出问题涉及的表和字段。

            user_behavior_fact:user_id,event_type(play/like/comment/share/follow/favorite),timestamp,content_id,creator_id,value(播放时长/计数),dimension(JSON)
            content_dim:content_id,title,tags(JSON),duration,creator_id,category,modality
            creator_dim:creator_id,name,followers,verified,category
            user_dim:user_id,age,gender(male/female),region
            time_dim:date,week,month,quarter,year
            activity_dim:activity_id,start_time,end_time,type,target_content(JSON)
            metric_def:metric_name,formula,dimension(JSON)

            关联:ubf.content_id=content_dim.content_id | ubf.creator_id=creator_dim.creator_id | ubf.user_id=user_dim.user_id | DATE(ubf.timestamp)=time_dim.date

            格式:
            相关表-表名:字段
            关联-JOIN
            """;

    private final ChatClient chatClient;

    public SchemaAgent(@Qualifier("cheapChatModel") ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    /**
     * 为用户问题识别相关的schema元素。
     *
     * @return 裁剪后的schema描述（纯文本，非JSON）
     */
    public String identify(String question) {
        log.info("SchemaAgent (cheap model) identifying schema");
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }
}
