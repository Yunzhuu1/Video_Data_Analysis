package com.yunzhu.video_data_analysis.agent;

import com.yunzhu.video_data_analysis.dto.AnalysisReport;
import com.yunzhu.video_data_analysis.dto.CommentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 解释原始查询结果并生成结构化的 {@link AnalysisReport}。
 * 接受来自 RAGAgent 的可选 {@link CommentResult}，以便将归因
 * 结论建立在真实的用户反馈之上。
 * 使用<b>强大模型</b> (gpt-4o) 进行复杂推理。
 */
@Component
public class InsightAgent {

    private static final Logger log = LoggerFactory.getLogger(InsightAgent.class);

    private static final String SYSTEM_PROMPT = """
            数据分析师。生成结构化JSON报告。
            总结发现→异常归因→生成图表配置(line/bar/pie)。
            有【用户评论分析】时融入归因段落。
            不含建议(另有专家)。不含图表外的多余文字。
            """;

    private final ChatClient chatClient;

    public InsightAgent(@Qualifier("strongChatModel") ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    public AnalysisReport analyze(String question, String queryResult,
                                   String schemaContext, CommentResult commentResult) {
        String ragSection = formatRagContext(commentResult);
        log.info("InsightAgent (strong) generating report{}",
                ragSection.isEmpty() ? "" : " (with RAG context)");

        return chatClient.prompt()
                .user(u -> u.text("""
                        用户问题: {question}

                        查询到的数据:
                        {data}

                        Schema上下文（参考）:
                        {schema}

                        {rag}

                        请基于以上数据生成结构化分析报告。
                        如果存在用户评论分析，请将其中的发现融入归因分析。
                        """)
                        .param("question", question)
                        .param("data", queryResult)
                        .param("schema", schemaContext)
                        .param("rag", ragSection))
                .call()
                .entity(AnalysisReport.class);
    }

    private static String formatRagContext(CommentResult cr) {
        if (cr == null || cr.getThemes() == null || cr.getThemes().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【用户评论分析】\n");
        sb.append("核心主题: ").append(String.join("、", cr.getThemes())).append("\n");
        sb.append("负面评论占比: ").append(String.format("%.0f%%", cr.getNegativeRatio() * 100)).append("\n");
        if (cr.getRepresentativeComments() != null && !cr.getRepresentativeComments().isEmpty()) {
            sb.append("代表性评论:\n");
            cr.getRepresentativeComments().forEach(c -> sb.append("- ").append(c).append("\n"));
        }
        if (cr.getSummary() != null && !cr.getSummary().isEmpty()) {
            sb.append("总结: ").append(cr.getSummary()).append("\n");
        }
        return sb.toString();
    }
}
