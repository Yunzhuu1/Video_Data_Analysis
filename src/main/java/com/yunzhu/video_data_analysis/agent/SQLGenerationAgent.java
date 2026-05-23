package com.yunzhu.video_data_analysis.agent;

import com.yunzhu.video_data_analysis.tool.MetricQueryTool;
import com.yunzhu.video_data_analysis.tool.SqlExecutionTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class SQLGenerationAgent {

    private static final Logger log = LoggerFactory.getLogger(SQLGenerationAgent.class);

    private static final String SYSTEM_PROMPT = """
            SQL专家。按步骤执行：
            1. 涉及指标→先getMetricFormula
            2. 写SQL→executeSql
            3. 报错→修正重试×3
            4. 返回数据

            规则：SELECT only。JSON用->>。时间直接比timestamp。
            COALESCE防NULL。executeSql限100行，超限用GROUP BY+LIMIT。
            表结构见下方上下文，勿臆测字段。

            【性能优化】聚合查询(SUM/AVG/COUNT/GROUP BY)优先查 metric_daily 表。
            metric_daily(date,category,total_plays,total_play_duration,total_likes,total_comments)
            包含每日每分类的预聚合数据，仅数千行。
            只有当 metric_daily 没有所需粒度时(如按创作者/用户维度)，才回退到 user_behavior_fact。

            【自查】返回前检查SQL逻辑：
            - 聚合查询是否遗漏了WHERE/event_type过滤？
            - JOIN条件是否正确匹配了外键？
            发现问题则用executeSql重新执行修正后的SQL。
            """;

    private final ChatClient chatClient;

    public SQLGenerationAgent(@Qualifier("strongChatModel") ChatModel chatModel,
                              SqlExecutionTool sqlExecutionTool,
                              MetricQueryTool metricQueryTool) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(sqlExecutionTool, metricQueryTool)
                .build();
    }

    /** 首次尝试 — 无先前反馈。 */
    public String execute(String question, String schemaContext) {
        return execute(question, schemaContext, null);
    }

    /**
     * 执行时可选来自执行指导的反馈。
     * 如果 {@code previousFeedback} 非空，模型会将其作为
     * 附加上下文来指导修正。
     */
    public String execute(String question, String schemaContext, String previousFeedback) {
        log.info("SQLGenerationAgent (strong) executing query{}",
                previousFeedback != null ? " (with execution guidance feedback)" : "");

        String fb = previousFeedback != null
                ? "\n\n【上一轮SQL的校验反馈】\n" + previousFeedback + "\n请修正后重新用executeSql执行。"
                : "";

        return chatClient.prompt()
                .user(u -> u.text("""
                        用户问题: {question}

                        【上下文表结构】
                        {schema}

                        请根据上面提供的表结构信息和用户问题，生成SQL并执行。
                        {feedback}
                        """)
                        .param("question", question)
                        .param("schema", schemaContext)
                        .param("feedback", fb))
                .call()
                .content();
    }
}
