package com.yunzhu.video_data_analysis.agent;

import com.yunzhu.video_data_analysis.dto.AnalysisReport;
import com.yunzhu.video_data_analysis.service.SemanticCacheService;
import com.yunzhu.video_data_analysis.service.TokenUsageService;
import com.yunzhu.video_data_analysis.tool.MetricQueryTool;
import com.yunzhu.video_data_analysis.tool.SqlExecutionTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.util.function.Consumer;

/**
 * 数据分析智能体系统的Facade。
 * <p>
 * 两种模式，两种模型：
 * <ul>
 *   <li><b>流式聊天</b> — 单体 {@link ChatClient} 带记忆；
 *       使用<b>强大模型</b> (gpt-4o) 保证对话质量。</li>
 *   <li><b>结构化分析</b> — 路由到以下任一方式：
 *       <ul>
 *         <li><b>简单路径</b> — 单个 {@link ChatClient} 带工具；
 *             使用<b>廉价模型</b> (gpt-4o-mini) 进行基本查询。</li>
 *         <li><b>复杂路径</b> — {@link CoordinatorAgent} 多智能体管道；
 *             异构模型（schema/路由用廉价模型，SQL/洞察用强大模型）。</li>
 *       </ul>
 * </ul>
 */
@Component
public class DataAnalysisAgent {

    private static final Logger log = LoggerFactory.getLogger(DataAnalysisAgent.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 流式聊天（强大模型 + 记忆）。 */
    private final ChatClient chatClient;
    /** 简单分析（廉价模型 + 工具，无记忆）。 */
    private final ChatClient simpleAnalysisClient;

    private final CoordinatorAgent coordinatorAgent;
    private final RouterAgent routerAgent;
    private final SemanticCacheService cacheService;
    private final TokenUsageService tokenUsageService;

    public DataAnalysisAgent(@Qualifier("strongChatModel") ChatModel strongChatModel,
                             @Qualifier("cheapChatModel") ChatModel cheapChatModel,
                             SqlExecutionTool sqlExecutionTool,
                             MetricQueryTool metricQueryTool,
                             ChatMemory chatMemory,
                             SemanticCacheService cacheService,
                             TokenUsageService tokenUsageService,
                             CoordinatorAgent coordinatorAgent,
                             RouterAgent routerAgent) {
        this.cacheService = cacheService;
        this.tokenUsageService = tokenUsageService;
        this.coordinatorAgent = coordinatorAgent;
        this.routerAgent = routerAgent;

        // 流式聊天：强大模型 + 记忆用于多轮对话
        this.chatClient = ChatClient.builder(strongChatModel)
                .defaultSystem(STREAM_SYSTEM_PROMPT)
                .defaultTools(sqlExecutionTool, metricQueryTool)
                .defaultAdvisors(
                        SimpleLoggerAdvisor.builder().order(1).build(),
                        MessageChatMemoryAdvisor.builder(chatMemory).order(0).build())
                .build();

        // 简单分析：廉价模型 + 工具，无记忆开销
        this.simpleAnalysisClient = ChatClient.builder(cheapChatModel)
                .defaultSystem(SIMPLE_ANALYSIS_PROMPT)
                .defaultTools(sqlExecutionTool, metricQueryTool)
                .defaultAdvisors(SimpleLoggerAdvisor.builder().order(1).build())
                .build();
    }

    // ==================== 流式聊天 ====================

    public Flux<String> chat(String userId, String userMessage) {
        return chatClient.prompt()
                .user(userMessage)
                .advisors(a -> a.param("chat_memory_conversation_id", userId))
                .stream()
                .content();
    }

    // ==================== 结构化分析 ====================

    public AnalysisReport analyze(String userId, String userMessage) {
        return analyze(userId, userMessage, null);
    }

    /** 带进度回调的分析入口，{@code onProgress} 传递 "step:消息" 字符串。 */
    public AnalysisReport analyze(String userId, String userMessage, Consumer<String> onProgress) {
        return analyze(userId, userMessage, onProgress, false);
    }

    /** 带进度回调 + 缓存跳过开关，{@code bypassCache=true} 时直接查数据库。 */
    public AnalysisReport analyze(String userId, String userMessage, Consumer<String> onProgress, boolean bypassCache) {
        // 1. 语义缓存（调试时可跳过）
        if (!bypassCache) {
            String cachedJson = cacheService.get(userMessage);
            if (cachedJson != null) {
                try {
                    AnalysisReport cached = MAPPER.readValue(cachedJson, AnalysisReport.class);
                    tokenUsageService.record(userId, userMessage, 0, 0, "cache", true);
                    log.info("Cache HIT for: {}", userMessage);
                    return cached;
                } catch (Exception e) {
                    log.warn("Cache deserialization failed, re-executing: {}", e.getMessage());
                }
            }
        } else {
            log.info("Cache bypassed for: {}", userMessage);
        }

        // 2. 路由
        AnalysisReport result;
        if (routerAgent.isSimple(userMessage)) {
            log.info("Routing to SIMPLE path (cheap model)");
            result = simpleAnalyze(userMessage);
        } else {
            log.info("Routing to COMPLEX path (multi-agent, heterogeneous models)");
            result = coordinatorAgent.analyze(userId, userMessage, onProgress);
        }

        // 3. 缓存
        try {
            cacheService.put(userMessage, MAPPER.writeValueAsString(result));
        } catch (Exception e) {
            log.warn("Failed to serialize result for cache: {}", e.getMessage());
        }

        return result;
    }

    private AnalysisReport simpleAnalyze(String userMessage) {
        try {
            var responseEntity = simpleAnalysisClient.prompt()
                    .user(userMessage)
                    .call()
                    .responseEntity(AnalysisReport.class);
            AnalysisReport result = responseEntity.entity();
            var chatResponse = responseEntity.response();
            var meta = chatResponse.getMetadata();
            var usage = meta != null ? meta.getUsage() : null;
            int p = usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
            int c = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
            tokenUsageService.record("simple", userMessage, p, c,
                    meta != null ? meta.getModel() : "cheap-model", false);
            log.info("Simple analysis (cheap) | prompt_tokens={} | completion_tokens={}", p, c);
            return result;
        } catch (Exception e) {
            log.warn("Simple analysis JSON parse failed: {}", e.getMessage());
            AnalysisReport fallback = new AnalysisReport();
            fallback.setSummary("查询失败，请尝试使用分析报告页面。");
            fallback.setPeriod("—");
            return fallback;
        }
    }

    // ==================== 系统提示词 ====================

    private static final String STREAM_SYSTEM_PROMPT = """
            你是短视频数据分析师。查数据库回答用户问题。

            工具：getMetricFormula, executeSql

            数据返回后，用简洁格式呈现结果。如果数据为空就如实说。

            【数据库】
            事实表 user_behavior_fact: user_id, event_type(play/like/comment/share/follow/favorite), timestamp, content_id, creator_id, value, dimension(JSON)
            视频 content_dim: content_id, title, category, duration
            创作者 creator_dim: creator_id, name, followers, category
            预聚合 metric_daily: date, category, total_plays, total_play_duration, total_likes, total_comments

            已知分类: 美妆, 游戏, 美食。时间范围: 2023-10-01~2023-10-31。
            JOIN: ubf.content_id=content_dim.content_id, ubf.creator_id=creator_dim.creator_id

            规则：SELECT only。聚合查 metric_daily。JSON用->>。时间用timestamp比较。
            """;

    private static final String SIMPLE_ANALYSIS_PROMPT = """
            你是一个短视频平台数据分析师。根据用户的问题查询数据库，生成结构化的分析报告。

            可用工具：getMetricFormula, executeSql

            工作流程（必须遵守）：
            1. 第一步永远是调用 executeSql 查数据库
            2. 拿到数据后生成报告
            3. 不要凭已知信息直接回答——数据必须来自数据库

            【数据库】
            content_dim: content_id(主键), title(标题), category(分类), duration(时长秒), creator_id
            creator_dim: creator_id, name(名称), followers(粉丝数), category(领域)
            user_dim: user_id, age, gender(male/female), region(地区)
            user_behavior_fact: user_id, event_type(play/like/comment/share/follow/favorite), timestamp, value, content_id
            metric_daily: date, category, total_plays, total_play_duration, total_likes

            已知分类值: 美妆, 游戏, 美食。时间范围: 2023-10-01~2023-10-31。
            JOIN: ubf.content_id=content_dim.content_id, ubf.creator_id=creator_dim.creator_id, ubf.user_id=user_dim.user_id

            规则：SELECT only。必须输出 JSON 格式的 AnalysisReport，不要输出其他文字。
            """;
}
