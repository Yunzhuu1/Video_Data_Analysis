package com.yunzhu.video_data_analysis.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunzhu.video_data_analysis.dto.CommentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 根据查询结果生成业务建议。
 * 与 {@link InsightAgent} <b>并行</b>运行。
 * <p>
 * 使用<b>廉价模型</b> (gpt-4o-mini)，因为从结构化数据生成建议
 * 是模式匹配任务，而不是深度推理任务。
 */
@Component
public class RecommendationAgent {

    private static final Logger log = LoggerFactory.getLogger(RecommendationAgent.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            基于数据给运营建议。最多3条，每条一句话。
            输出JSON数组 ["建议1","建议2"]。
            只输出JSON。
            """;

    private final ChatClient chatClient;

    public RecommendationAgent(@Qualifier("cheapChatModel") ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    public List<String> recommend(String question, String queryResult, String schemaContext) {
        return recommend(question, queryResult, schemaContext, null);
    }

    public List<String> recommend(String question, String queryResult, String schemaContext, CommentResult ragResult) {
        log.info("RecommendationAgent (cheap model) generating suggestions{}",
                ragResult != null ? " (with RAG context)" : "");

        String ragSection = formatRagContext(ragResult);

        String json = chatClient.prompt()
                .user(u -> u.text("""
                        用户问题: {question}
                        查询到的数据: {data}
                        Schema上下文（参考）: {schema}
                        {rag}
                        请给出基于数据的运营建议，最多3条。
                        """)
                        .param("question", question)
                        .param("data", queryResult)
                        .param("schema", schemaContext)
                        .param("rag", ragSection))
                .call()
                .content();

        if (json == null || json.trim().isEmpty()) return List.of();
        try {
            return MAPPER.readValue(json.trim(), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse recommendations: {}", e.getMessage());
            return List.of();
        }
    }

    private static String formatRagContext(CommentResult cr) {
        if (cr == null || cr.getThemes() == null || cr.getThemes().isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【用户评论反馈参考】\n");
        sb.append("核心主题: ").append(String.join("、", cr.getThemes())).append("\n");
        sb.append("负面评论占比: ").append(String.format("%.0f%%", cr.getNegativeRatio() * 100)).append("\n");
        if (cr.getRepresentativeComments() != null && !cr.getRepresentativeComments().isEmpty()) {
            sb.append("代表性评论:\n");
            for (String c : cr.getRepresentativeComments()) {
                sb.append("- ").append(c).append("\n");
            }
        }
        sb.append("请参考以上用户反馈，给出针对性的运营建议。\n");
        return sb.toString();
    }
}
