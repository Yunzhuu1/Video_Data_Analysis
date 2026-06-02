package com.yunzhu.video_data_analysis.agent;

import com.yunzhu.video_data_analysis.dto.AnalysisReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * DBQA（Database-Backed Quality Assurance）Agent。
 * 在 InsightAgent 产出报告后，验证报告是否完整准确地回答了用户问题。
 * <p>
 * 如果报告存在缺口（缺少某个维度、数据不支持结论、遗漏对比等），
 * DBQA 输出具体的缺失项，CoordinatorAgent 据此触发补充查询和修正。
 * <p>
 * 最多允许 1 轮修正，防止无限循环。
 */
@Component
public class DbqaAgent {

    private static final Logger log = LoggerFactory.getLogger(DbqaAgent.class);

    private static final String DBQA_PROMPT = """
            验证分析报告是否完整回答了用户问题。输出 PASS 或 FAIL:具体缺失描述。

            用户问题: {question}
            分析报告摘要: {summary}
            核心指标: {metrics}
            查询数据: {data}

            检查：
            - 报告是否直接回答了问题？（如果问分类对比，是否每个分类都有数据？）
            - 是否有明确提到的维度在报告中缺失？（如问"对比"但只给了一个分类的数据）
            - 报表中的结论是否有数据支撑？（归因是否引用了评论或交叉验证数据？）

            如果完整 → PASS
            如果不完整 → FAIL:具体缺失描述，尽可能精确到缺少什么数据
            """;

    private static final String RECHECK_PROMPT = """
            验证补充后的分析报告是否已解决问题。

            用户问题: {question}
            缺少的数据已补充: {gapData}
            补充后的报告摘要: {summary}

            现在报告是否完整了？完整→PASS 仍有缺失→FAIL:缺失描述
            """;

    private final ChatClient chatClient;

    public DbqaAgent(@Qualifier("cheapChatModel") ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("你是报告质量评审，检查分析报告是否完整回答了用户问题。")
                .build();
    }

    /**
     * 首次检查报告质量。
     *
     * @return DbqaResult (pass=true 表示通过, pass=false 时 feedback 包含缺失描述)
     */
    public DbqaResult check(String question, AnalysisReport report, String queryResult) {
        String summary = report != null ? report.getSummary() : "";
        String metrics = report != null && report.getMetrics() != null
                ? report.getMetrics().stream()
                    .map(m -> m.getName() + "=" + m.getValue())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("")
                : "";
        String preview = truncate(queryResult, 200);

        String answer = chatClient.prompt()
                .user(u -> u.text(DBQA_PROMPT)
                        .param("question", question)
                        .param("summary", summary)
                        .param("metrics", metrics)
                        .param("data", preview))
                .call()
                .content();

        boolean pass = answer != null && answer.trim().toUpperCase().startsWith("PASS");
        log.info("DBQA check | pass={}", pass);
        if (!pass && answer != null) {
            log.info("DBQA feedback: {}", answer);
        }

        String missingQuery = pass ? null : generateGapQuery(answer);
        return new DbqaResult(pass, pass ? null : answer, missingQuery);
    }

    /**
     * 补充数据后的复检。
     */
    public DbqaResult recheck(String question, AnalysisReport report, String gapData) {
        String summary = report != null ? report.getSummary() : "";

        String answer = chatClient.prompt()
                .user(u -> u.text(RECHECK_PROMPT)
                        .param("question", question)
                        .param("gapData", truncate(gapData, 200))
                        .param("summary", summary))
                .call()
                .content();

        boolean pass = answer != null && answer.trim().toUpperCase().startsWith("PASS");
        return new DbqaResult(pass, pass ? null : answer, null);
    }

    /**
     * 从 DBQA 输出中解析出可能需要的补充查询方向。
     * 当前用简单关键词匹配提取，后续可升级为 LLM 结构化输出。
     */
    private static String generateGapQuery(String feedback) {
        if (feedback == null) return null;
        String lower = feedback.toLowerCase();

        if (lower.contains("美食") && lower.contains("游戏")) return "category";
        if (lower.contains("对比") || lower.contains("比较")) return "category";
        if (lower.contains("北京") || lower.contains("上海")) return "region";
        if (lower.contains("流失") || lower.contains("跳出")) return "drop_off";

        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** DBQA 检查结果 */
    public record DbqaResult(boolean pass, String feedback, String missingQuery) {}
}
