package com.yunzhu.video_data_analysis.agent;

import com.yunzhu.video_data_analysis.service.SqlDialectService;
import com.yunzhu.video_data_analysis.tool.MetricQueryTool;
import com.yunzhu.video_data_analysis.tool.SqlExecutionTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * SQL 生成 Agent。System Prompt 中的 SQL 规则由 {@link SqlDialectService}
 * 根据数据库类型动态生成，不硬编码 MySQL 方言。
 */
@Component
public class SQLGenerationAgent {

    private static final Logger log = LoggerFactory.getLogger(SQLGenerationAgent.class);

    private static final String PROMPT_TEMPLATE = """
            SQL专家。按步骤执行：
            1. 涉及指标→先getMetricFormula
            2. 写SQL→executeSql
            3. 报错→修正重试×3
            4. 返回数据

            {sql_rules}

            【自查】返回前检查SQL逻辑：
            - 聚合查询是否遗漏了必要的WHERE过滤条件？
            - JOIN条件是否正确匹配了外键？
            发现问题则用executeSql重新执行修正后的SQL。
            """;

    private final ChatClient chatClient;

    public SQLGenerationAgent(@Qualifier("strongChatModel") ChatModel chatModel,
                              SqlExecutionTool sqlExecutionTool,
                              MetricQueryTool metricQueryTool,
                              SqlDialectService dialectService) {
        String sqlRules = dialectService.isInitialized()
                ? dialectService.getSqlRules()
                : "规则：SELECT only。简单SQL先查metric_daily预聚合表。";
        String systemPrompt = PROMPT_TEMPLATE.replace("{sql_rules}", sqlRules);

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultTools(sqlExecutionTool, metricQueryTool)
                .build();
    }

    /** 首次尝试 — 无先前反馈。 */
    public String execute(String question, String schemaContext) {
        return execute(question, schemaContext, null);
    }

    /**
     * 执行时可选来自执行指导的反馈。
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
