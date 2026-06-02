package com.yunzhu.video_data_analysis.agent;

import com.yunzhu.video_data_analysis.dto.AnalysisReport;
import com.yunzhu.video_data_analysis.dto.CommentResult;
import com.yunzhu.video_data_analysis.tool.SqlExecutionTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 协调多智能体管道，包含<b>执行指导</b>：
 * <ol>
 *   <li>SchemaAgent（廉价）— 裁剪schema</li>
 *   <li>SQLGenerationAgent（强大）— 编写并执行SQL</li>
 *   <li><b>执行指导</b> — 使用廉价模型验证SQL结果；
 *       如果结果不合理则提供反馈重新执行</li>
 *   <li>并行扇出：RAGAgent（廉价）+ InsightAgent（强大）+ RecAgent（廉价）</li>
 * </ol>
 */
@Component
public class CoordinatorAgent {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorAgent.class);

    private static final String VALIDATION_PROMPT = """
            基于SQL文本和返回数据，分析是否有异常。输出具体分析或 PASS。
            用户问题: {question}
            SQL原文: {sql}
            执行结果: {result}
            表结构上下文: {schema}

            检查：
            - SQL 是否遗漏了必要的 WHERE 过滤条件？（参考表结构中是否有枚举类型的过滤字段）
            - 执行结果是否在合理范围内？
            - 数据和 SQL 逻辑是否匹配？

            如果异常，输出具体分析，格式：
            "SQL写法问题: {具体哪个条件遗漏} → 数据影响: {数据值异常} → 修正建议: {怎么改}"

            如果正常，只输出 PASS。
            """;

    private final SchemaAgent schemaAgent;
    private final SQLGenerationAgent sqlGenerationAgent;
    private final RAGAgent ragAgent;
    private final InsightAgent insightAgent;
    private final RecommendationAgent recommendationAgent;
    private final DbqaAgent dbqaAgent;
    private final ChatClient validator;
    private final Executor agentExecutor;
    private final JdbcTemplate jdbcTemplate;

    public CoordinatorAgent(SchemaAgent schemaAgent,
                            SQLGenerationAgent sqlGenerationAgent,
                            RAGAgent ragAgent,
                            InsightAgent insightAgent,
                            RecommendationAgent recommendationAgent,
                            DbqaAgent dbqaAgent,
                            @Qualifier("agentTaskExecutor") Executor agentExecutor,
                            @Qualifier("cheapChatModel") ChatModel cheapChatModel,
                            JdbcTemplate jdbcTemplate) {
        this.schemaAgent = schemaAgent;
        this.sqlGenerationAgent = sqlGenerationAgent;
        this.ragAgent = ragAgent;
        this.insightAgent = insightAgent;
        this.recommendationAgent = recommendationAgent;
        this.dbqaAgent = dbqaAgent;
        this.agentExecutor = agentExecutor;
        this.validator = ChatClient.builder(cheapChatModel)
                .defaultSystem("你是SQL质量评审。判断查询结果是否合理。")
                .build();
        this.jdbcTemplate = jdbcTemplate;
    }

    public AnalysisReport analyze(String userId, String question) {
        return analyze(userId, question, null);
    }

    public AnalysisReport analyze(String userId, String question, java.util.function.Consumer<String> onProgress) {
        log.info("=== CoordinatorAgent: complex pipeline start ===");

        // 步骤1：Schema裁剪（廉价，顺序执行）
        accept(onProgress, "schema:正在检索相关表结构...");
        log.info("[1/6] SchemaAgent (cheap) pruning schema...");
        String schemaContext = schemaAgent.identify(question);

        // 步骤2：SQL生成和执行（强大，顺序执行）
        accept(onProgress, "sql:正在生成并执行SQL...");
        log.info("[2/6] SQLGenerationAgent (strong) executing SQL...");
        String queryResult = sqlGenerationAgent.execute(question, schemaContext);

        // 步骤3：执行指导 — 验证SQL结果，如果可疑则重新执行
        accept(onProgress, "validate:正在校验数据合理性...");
        log.info("[3/6] Execution Guidance — validating SQL result...");
        String feedback = validateResult(question, queryResult, SqlExecutionTool.getLastExecutedSql(), schemaContext);
        if (feedback != null) {
            log.warn("Execution Guidance triggered: {}", feedback);
            queryResult = sqlGenerationAgent.execute(question, schemaContext, feedback);
        } else {
            log.info("Execution Guidance: result looks reasonable, proceeding");
        }

        // 步骤4：RAG 检索（需要 SQL 结果才能搜评论）
        accept(onProgress, "rag:正在检索用户评论...");
        log.info("[4/6] RAGAgent (cheap) searching comments...");
        CommentResult ragResult = ragAgent.analyze(question, queryResult);

        // 步骤5：交叉验证 — 拿着 RAG 发现的主题去 play_detail 验证
        accept(onProgress, "cross-ref:正在交叉验证归因...");
        log.info("[5/6] Cross-validation — verifying RAG themes with play_detail...");
        String crossValidation = crossValidate(ragResult);
        log.debug("Cross-validation result: {}", truncate(crossValidation, 150));

        // 最终结果 — 对下面的lambda必须是effectively final
        String finalQueryResult = queryResult;
        String finalCrossValidation = crossValidation;
        CommentResult finalRagResult = ragResult;

        // 步骤6：Insight + Rec 真并行（两者都依赖 RAG 结果，互相不依赖）
        accept(onProgress, "parallel:正在生成分析与建议...");
        log.info("[6/6] Parallel: InsightAgent (strong) + RecAgent (cheap) (both with RAG context)");

        CompletableFuture<AnalysisReport> insightFuture = CompletableFuture
                .supplyAsync(() -> insightAgent.analyze(question, finalQueryResult, schemaContext, finalRagResult, finalCrossValidation),
                        agentExecutor)
                .orTimeout(60, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.error("InsightAgent failed", ex);
                    return fallbackReport(question, finalQueryResult);
                });

        CompletableFuture<List<String>> recFuture = CompletableFuture
                .supplyAsync(() -> recommendationAgent.recommend(question, finalQueryResult, schemaContext, finalRagResult),
                        agentExecutor)
                .orTimeout(30, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.error("RecommendationAgent failed", ex);
                    return List.of();
                });

        // 合并
        accept(onProgress, "merge:正在合并生成最终报告...");
        CompletableFuture.allOf(insightFuture, recFuture).join();

        AnalysisReport report = insightFuture.join();
        List<String> recs = recFuture.join();
        if (recs != null && !recs.isEmpty()) report.setRecommendations(recs);

        // 步骤7: DBQA 质量检查（ReAct 循环，最多 1 轮修正）
        accept(onProgress, "dbqa:正在验证报告完整性...");
        log.info("[7/7] DBQA — verifying report completeness...");

        DbqaAgent.DbqaResult dbqa = dbqaAgent.check(question, report, finalQueryResult);
        if (!dbqa.pass() && dbqa.missingQuery() != null) {
            log.warn("DBQA triggered: {}", dbqa.feedback());

            // ReAct 第一轮：补充查询 + 局部修正
            String gapQuery = buildGapQuery(dbqa.missingQuery(), question);
            String gapData = "";
            if (gapQuery != null) {
                gapData = sqlGenerationAgent.execute(question + "（补充查询）", schemaContext,
                        "用户问题缺少维度: " + dbqa.feedback() + "。请根据以上缺失维度补充查询数据。");
            }

            // 重新生成报告（带着缺口数据和修正指示）
            String expandedQueryResult = finalQueryResult + "\n\n【补充数据】\n" + gapData;
            report = insightAgent.analyze(question, expandedQueryResult, schemaContext,
                    finalRagResult, finalCrossValidation);

            // 重新注入建议
            if (recs != null && !recs.isEmpty()) report.setRecommendations(recs);

            // DBQA 复检
            dbqa = dbqaAgent.recheck(question, report, gapData);
            if (!dbqa.pass()) {
                log.warn("DBQA recheck still failed after correction, proceeding with current report");
            } else {
                log.info("DBQA recheck passed after targeted correction");
            }
        }

        accept(onProgress, "complete:分析完成");
        log.info("=== CoordinatorAgent: pipeline complete ===");
        return report;
    }

    private static void accept(java.util.function.Consumer<String> cb, String msg) {
        if (cb != null) cb.accept(msg);
    }

    /**
     * 使用廉价模型验证SQL结果。
     * @return 如果可疑则返回FAIL原因字符串，如果通过则返回null
     */
    private String validateResult(String question, String queryResult, String sqlText, String schemaContext) {
        String preview = truncate(queryResult, 300);
        if (preview == null || preview.trim().isEmpty()) {
            return "返回结果为空，请检查SQL是否正确";
        }
        String sql = sqlText != null && !sqlText.isEmpty() ? sqlText : "（无法获取SQL原文）";
        String schema = schemaContext != null && !schemaContext.isEmpty() ? schemaContext : "（无表结构信息）";

        String answer = validator.prompt()
                .user(u -> u.text(VALIDATION_PROMPT)
                        .param("question", question)
                        .param("sql", truncate(sql, 500))
                        .param("result", preview)
                        .param("schema", truncate(schema, 500)))
                .call()
                .content();

        if (answer != null && !answer.trim().toUpperCase().startsWith("PASS")) {
            return answer.trim();
        }
        return null;
    }

    /**
     * 交叉验证：拿着 RAGAgent 发现的主题，有针对性查询 play_detail 验证。
     * 如果 RAG 说"广告太多"，这里查美食类在广告区间的跳出占比；
     * 如果 RAG 没发现主题或 confidence 低，不做针对性验证，返回空。
     */
    private String crossValidate(CommentResult ragResult) {
        if (ragResult == null || ragResult.getThemes() == null || ragResult.getThemes().isEmpty()
                || ragResult.getConfidence() < 0.3) {
            // RAG 没找到可信评论，不做交叉验证
            return "";
        }
        try {
            String themes = String.join(" ", ragResult.getThemes());
            StringBuilder sb = new StringBuilder("【播放数据交叉验证】\n");
            sb.append("RAG 主题: ").append(themes).append("\n");

            // 按 RAG 主题词语义选择验证维度
            boolean hasAd = themes.contains("广告") || themes.contains("卡顿");
            boolean hasActivity = themes.contains("活动") || themes.contains("内容");

            // 各分类跳出时间分布
            List<Map<String, Object>> dropOffs = jdbcTemplate.queryForList(
                    "SELECT cd.category, ROUND(AVG(pd.drop_off_second)) as avg_drop_off, "
                    + "COUNT(*) as plays, ROUND(AVG(pd.play_duration),0) as avg_duration "
                    + "FROM play_detail pd JOIN content_dim cd ON pd.content_id = cd.content_id "
                    + "GROUP BY cd.category");
            for (var row : dropOffs) {
                sb.append("  ").append(row.get("category"))
                  .append(": 平均跳出").append(row.get("avg_drop_off")).append("s")
                  .append(", 观看").append(row.get("avg_duration")).append("s")
                  .append("(").append(row.get("plays")).append("次)\n");
            }

            // 如果是广告/卡顿主题：验证广告区间的跳出率
            if (hasAd) {
                List<Map<String, Object>> adZone = jdbcTemplate.queryForList(
                        "SELECT cd.category, "
                        + "ROUND(COUNT(CASE WHEN pd.drop_off_second BETWEEN 10 AND 25 THEN 1 END) * 100.0 / COUNT(*), 1) as ad_zone_rate "
                        + "FROM play_detail pd JOIN content_dim cd ON pd.content_id = cd.content_id "
                        + "GROUP BY cd.category ORDER BY ad_zone_rate DESC");
                sb.append("广告区间(10-25s)跳出占比:\n");
                for (var row : adZone) {
                    String cat = (String) row.get("category");
                    Object rate = row.get("ad_zone_rate");
                    if ("美食".equals(cat)) {
                        sb.append("  ▸ ").append(cat).append(": ").append(rate).append("% ⚠️ 重点关注\n");
                    } else {
                        sb.append("  ").append(cat).append(": ").append(rate).append("%\n");
                    }
                }
            }

            // 如果是活动主题：验证活动前后的播放量变化
            if (hasActivity) {
                List<Map<String, Object>> actComp = jdbcTemplate.queryForList(
                        "SELECT cd.category, "
                        + "AVG(CASE WHEN pd.created_at < '2023-10-08' THEN pd.completion_rate END) as during_comp, "
                        + "AVG(CASE WHEN pd.created_at >= '2023-10-08' THEN pd.completion_rate END) as after_comp "
                        + "FROM play_detail pd JOIN content_dim cd ON pd.content_id = cd.content_id "
                        + "GROUP BY cd.category");
                sb.append("活动前后完播率对比:\n");
                for (var row : actComp) {
                    sb.append("  ").append(row.get("category"))
                      .append(": ").append(String.format("%.1f", row.get("during_comp"))).append("% → ")
                      .append(String.format("%.1f", row.get("after_comp"))).append("%\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("Cross-validation query failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 根据 DBQA 的 missingQuery 生成具体的补充 SQL 方向。
     * 由 DbqaAgent 解析出缺失维度，这里映射为可执行的查询意图。
     */
    private static String buildGapQuery(String missingQuery, String question) {
        if (missingQuery == null) return null;
        return switch (missingQuery) {
            case "category" -> "对比各分类的完播率、播放量，按分类分组返回";
            case "region" -> "按地区分组统计完播率和播放量，对比北京上海广州";
            case "drop_off" -> "统计各分类在广告区间的跳出率，按分类分组";
            default -> null;
        };
    }

    private static AnalysisReport fallbackReport(String question, String queryResult) {
        var report = new AnalysisReport();
        report.setSummary("分析报告生成失败。查询到以下原始数据：" + queryResult);
        report.setPeriod("—");
        return report;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
