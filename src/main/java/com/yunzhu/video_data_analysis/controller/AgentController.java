package com.yunzhu.video_data_analysis.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunzhu.video_data_analysis.agent.DataAnalysisAgent;
import com.yunzhu.video_data_analysis.dto.AnalysisReport;
import com.yunzhu.video_data_analysis.service.SemanticCacheService;
import com.yunzhu.video_data_analysis.service.TokenUsageService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataAnalysisAgent dataAnalysisAgent;
    private final SemanticCacheService cacheService;
    private final TokenUsageService tokenUsageService;
    private final Executor agentExecutor;

    public AgentController(DataAnalysisAgent dataAnalysisAgent,
                           SemanticCacheService cacheService,
                           TokenUsageService tokenUsageService,
                           @Qualifier("agentTaskExecutor") Executor agentExecutor) {
        this.dataAnalysisAgent = dataAnalysisAgent;
        this.cacheService = cacheService;
        this.tokenUsageService = tokenUsageService;
        this.agentExecutor = agentExecutor;
    }

    /** SSE 流式对话 */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestParam String userId, @RequestParam String message) {
        return dataAnalysisAgent.chat(userId, message);
    }

    /** 结构化分析（同步 JSON），传 nocache=true 跳过语义缓存 */
    @GetMapping("/analyze")
    public AnalysisReport analyze(@RequestParam String userId, @RequestParam String message,
                                  @RequestParam(defaultValue = "false") boolean nocache) {
        return dataAnalysisAgent.analyze(userId, message, null, nocache);
    }

    /** 结构化分析（SSE 流式，每步推送进度事件），传 nocache=true 跳过语义缓存 */
    @GetMapping(value = "/analyze-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> analyzeStream(@RequestParam String userId, @RequestParam String message,
                                      @RequestParam(defaultValue = "false") boolean nocache) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        boolean skipCache = nocache;
        CompletableFuture.runAsync(() -> {
            try {
                dataAnalysisAgent.analyze(userId, message, progress -> {
                    sink.tryEmitNext("data: {\\\"type\\\":\\\"progress\\\",\\\"message\\\":\\\""
                            + progress + "\\\"}\n\n");
                }, skipCache);
                sink.tryEmitNext("data: {\"type\":\"complete\"}\n\n");
            } catch (Exception e) {
                sink.tryEmitNext("data: {\"type\":\"error\",\"message\":\"" + e.getMessage() + "\"}\n\n");
            } finally {
                sink.tryEmitComplete();
            }
        }, agentExecutor);

        return sink.asFlux();
    }

    // ==================== Admin ====================

    @GetMapping("/admin/cache")
    public Map<String, Object> cacheStats() {
        return Map.of("size", cacheService.size(), "threshold", 0.92);
    }

    @GetMapping("/admin/tokens")
    public TokenUsageService.TokenStats tokenStats() {
        return tokenUsageService.summary();
    }

    @GetMapping("/admin/recent")
    public java.util.List<TokenUsageService.UsageRecord> recentUsage() {
        return tokenUsageService.recent(20);
    }

    @GetMapping("jiegouhua/stats")
    public Map<String, Object> stats() {
        var tokenStats = tokenUsageService.summary();
        return Map.of(
                "cache", Map.of("size", cacheService.size(), "threshold", 0.92),
                "tokens", Map.of(
                        "totalCalls", tokenStats.totalCalls(),
                        "totalPromptTokens", tokenStats.totalPromptTokens(),
                        "totalCompletionTokens", tokenStats.totalCompletionTokens(),
                        "totalTokens", tokenStats.totalTokens(),
                        "totalCostUsd", tokenStats.totalCostUsd(),
                        "cachedCount", tokenStats.cachedCount(),
                        "uncachedCount", tokenStats.uncachedCount()
                )
        );
    }

    @PostMapping("/admin/cache/clear")
    public Map<String, String> clearCache() {
        cacheService.clear();
        return Map.of("status", "ok");
    }

    @PostMapping("/admin/tokens/clear")
    public Map<String, String> clearTokens() {
        tokenUsageService.clear();
        return Map.of("status", "ok");
    }
}
