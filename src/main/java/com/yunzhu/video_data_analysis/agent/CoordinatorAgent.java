package com.yunzhu.video_data_analysis.agent;

import com.yunzhu.video_data_analysis.dto.AnalysisReport;
import com.yunzhu.video_data_analysis.dto.CommentResult;
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
            评审SQL执行结果是否合理。输出 PASS 或 FAIL:原因。
            用户问题: {question}
            执行结果: {result}

            【指标合理范围参考】
            完播率: 0.25-0.55（低于0.20或高于0.70需警惕）
            互动率: 0.03-0.20
            播放量/天/视频: 100-5000（视热门程度浮动）
            event_type='play'的value应在1到视频时长之间，不应为1

            检查：
            - 聚合查询是否遗漏了event_type='play'过滤（完播率/播放量必须只对play事件聚合）
            - 数值是否偏离合理范围超过50%？
            - 0行数据是否合理（无数据或WHERE过严）
            - 所有值均为NULL/0可能有问题
            """;

    private final SchemaAgent schemaAgent;
    private final SQLGenerationAgent sqlGenerationAgent;
    private final RAGAgent ragAgent;
    private final InsightAgent insightAgent;
    private final RecommendationAgent recommendationAgent;
    private final ChatClient validator;
    private final Executor agentExecutor;
    private final JdbcTemplate jdbcTemplate;

    public CoordinatorAgent(SchemaAgent schemaAgent,
                            SQLGenerationAgent sqlGenerationAgent,
                            RAGAgent ragAgent,
                            InsightAgent insightAgent,
                            RecommendationAgent recommendationAgent,
                            @Qualifier("agentTaskExecutor") Executor agentExecutor,
                            @Qualifier("cheapChatModel") ChatModel cheapChatModel,
                            JdbcTemplate jdbcTemplate) {
        this.schemaAgent = schemaAgent;
        this.sqlGenerationAgent = sqlGenerationAgent;
        this.ragAgent = ragAgent;
        this.insightAgent = insightAgent;
        this.recommendationAgent = recommendationAgent;
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
        String feedback = validateResult(question, queryResult);
        if (feedback != null) {
            log.warn("Execution Guidance triggered: {}", feedback);
            queryResult = sqlGenerationAgent.execute(question, schemaContext, feedback);
        } else {
            log.info("Execution Guidance: result looks reasonable, proceeding");
        }

        // 步骤4：交叉验证 — 查 play_detail 验证评论中的广告/跳出指控
        accept(onProgress, "cross-ref:正在交叉验证归因...");
        log.info("[4/6] Cross-validation — verifying attribution with play_detail...");
        String crossValidation = crossValidate(queryResult);
        log.debug("Cross-validation result: {}", truncate(crossValidation, 150));

        // 最终结果 — 对下面的lambda必须是effectively final
        String finalQueryResult = queryResult;
        String finalCrossValidation = crossValidation;

        // 步骤5：RAG 检索（顺序执行 — 需要 SQL 结果才能搜评论）
        accept(onProgress, "rag:正在检索用户评论...");
        log.info("[5/6] RAGAgent (cheap) searching comments...");
        CommentResult ragResult = ragAgent.analyze(question, finalQueryResult);

        // 步骤6：Insight + Rec 真并行（两者都依赖 RAG 结果，互相不依赖）
        accept(onProgress, "parallel:正在生成分析与建议...");
        log.info("[6/6] Parallel: InsightAgent (strong) + RecAgent (cheap) (both with RAG context)");

        CompletableFuture<AnalysisReport> insightFuture = CompletableFuture
                .supplyAsync(() -> insightAgent.analyze(question, finalQueryResult, schemaContext, ragResult, finalCrossValidation),
                        agentExecutor)
                .orTimeout(60, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.error("InsightAgent failed", ex);
                    return fallbackReport(question, finalQueryResult);
                });

        CompletableFuture<List<String>> recFuture = CompletableFuture
                .supplyAsync(() -> recommendationAgent.recommend(question, finalQueryResult, schemaContext, ragResult),
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
    private String validateResult(String question, String queryResult) {
        String preview = truncate(queryResult, 300);
        if (preview == null || preview.trim().isEmpty()) {
            return "返回结果为空，请检查SQL是否正确";
        }

        String answer = validator.prompt()
                .user(u -> u.text(VALIDATION_PROMPT)
                        .param("question", question)
                        .param("result", preview))
                .call()
                .content();

        if (answer != null && answer.trim().startsWith("FAIL")) {
            return answer.trim().substring(5).trim();
        }
        return null; // PASS
    }

    /**
     * 交叉验证：查询 play_detail 中的跳出点，验证 RAG 评论中的归因指控。
     * 如果 RAG 说"广告太多"，这里会查实际跳出时间点是否集中在广告插入位。
     */
    private String crossValidate(String queryResult) {
        try {
            // 1. 按分类统计跳出时间点的分布
            List<Map<String, Object>> dropOffs = jdbcTemplate.queryForList(
                    "SELECT cd.category, ROUND(AVG(pd.drop_off_second)) as avg_drop_off, "
                    + "COUNT(*) as play_count, ROUND(AVG(pd.completion_rate),2) as avg_completion "
                    + "FROM play_detail pd JOIN content_dim cd ON pd.content_id = cd.content_id "
                    + "GROUP BY cd.category");
            if (dropOffs.isEmpty()) return "";

            // 2. 美食类视频的广告跳出率（验证"广告太多"指控）
            List<Map<String, Object>> foodDrop = jdbcTemplate.queryForList(
                    "SELECT COUNT(CASE WHEN pd.drop_off_second BETWEEN 10 AND 25 THEN 1 END) * 100.0 / COUNT(*) as ad_zone_drop_rate "
                    + "FROM play_detail pd JOIN content_dim cd ON pd.content_id = cd.content_id "
                    + "WHERE cd.category = '美食'");
            Map<String, Object> foodRow = foodDrop.isEmpty() ? Map.of() : foodDrop.get(0);

            // 3. 对比美食 vs 非美食的完播率
            List<Map<String, Object>> completionComp = jdbcTemplate.queryForList(
                    "SELECT cd.category, ROUND(AVG(pd.completion_rate),2) as avg_comp "
                    + "FROM play_detail pd JOIN content_dim cd ON pd.content_id = cd.content_id "
                    + "GROUP BY cd.category ORDER BY avg_comp");

            StringBuilder sb = new StringBuilder("【播放数据交叉验证】\n");
            sb.append("各分类跳出时间:\n");
            for (var row : dropOffs) {
                sb.append("  ").append(row.get("category")).append(": 平均跳出")
                  .append(row.get("avg_drop_off")).append("s, 完播率")
                  .append(row.get("avg_completion")).append("%\n");
            }
            if (foodRow.get("ad_zone_drop_rate") != null) {
                sb.append("美食类广告区间(10-25s)跳出占比: ")
                  .append(String.format("%.1f%%", foodRow.get("ad_zone_drop_rate"))).append("\n");
            }
            sb.append("完播率排名:\n");
            int rank = 1;
            for (var row : completionComp) {
                sb.append("  ").append(rank++).append(". ").append(row.get("category"))
                  .append(": ").append(row.get("avg_comp")).append("%\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
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
