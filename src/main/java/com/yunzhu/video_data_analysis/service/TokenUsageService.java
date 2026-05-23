package com.yunzhu.video_data_analysis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 跟踪每次LLM调用的token使用量和估计成本。
 * <p>
 * 提供聚合统计用于监控和成本分析。
 * 数据存储在内存中（非持久化，用于演示目的）。
 */
@Service
public class TokenUsageService {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageService.class);

    /** gpt-4o每1K prompt tokens的估计成本（美元）。根据需要调整。 */
    private static final double PROMPT_COST_PER_1K = 0.0025;
    /** gpt-4o每1K completion tokens的估计成本（美元）。 */
    private static final double COMPLETION_COST_PER_1K = 0.01;

    private final CopyOnWriteArrayList<UsageRecord> records = new CopyOnWriteArrayList<>();

    /**
     * 记录使用条目。
     *
     * @param userId          发出请求的用户
     * @param query           查询文本
     * @param promptTokens    prompt token数量
     * @param completionTokens completion token数量
     * @param model           模型标识符（例如 "gpt-4o"）
     * @param cached          是否为缓存命中
     */
    public void record(String userId, String query,
                       Integer promptTokens, Integer completionTokens,
                       String model, boolean cached) {
        int p = promptTokens != null ? promptTokens : 0;
        int c = completionTokens != null ? completionTokens : 0;
        double cost = p / 1000.0 * PROMPT_COST_PER_1K + c / 1000.0 * COMPLETION_COST_PER_1K;

        records.add(new UsageRecord(
                LocalDateTime.now(),
                userId, truncate(query, 60),
                p, c, p + c,
                Math.round(cost * 100_000.0) / 100_000.0,
                model, cached
        ));

        log.debug("Token usage recorded | userId={} | prompt={} | completion={} | cost=${} | cached={}",
                userId, p, c, String.format("%.5f", cost), cached);
    }

    /** 记录的API调用总数。 */
    public int totalCalls() { return records.size(); }

    /** 服务的缓存响应数量。 */
    public long cachedCalls() { return records.stream().filter(r -> r.cached).count(); }

    /** 聚合token统计。 */
    public TokenStats summary() {
        if (records.isEmpty()) return new TokenStats(0, 0, 0, 0, 0.0, 0, 0);

        int totalPrompt = records.stream().mapToInt(r -> r.promptTokens).sum();
        int totalCompletion = records.stream().mapToInt(r -> r.completionTokens).sum();
        int totalTokens = totalPrompt + totalCompletion;
        double totalCost = records.stream().mapToDouble(r -> r.cost).sum();
        long cachedCount = cachedCalls();
        long uncachedCount = records.size() - cachedCount;

        return new TokenStats(records.size(), totalPrompt, totalCompletion, totalTokens,
                Math.round(totalCost * 100_000.0) / 100_000.0,
                (int) cachedCount, (int) uncachedCount);
    }

    /** 最近的使用记录（用于显示）。 */
    public List<UsageRecord> recent(int limit) {
        int from = Math.max(0, records.size() - limit);
        return records.subList(from, records.size());
    }

    public void clear() {
        records.clear();
        log.info("Token usage records cleared");
    }

    /* ==================== records ==================== */

    public record UsageRecord(
            LocalDateTime timestamp,
            String userId,
            String query,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            double cost,
            String model,
            boolean cached
    ) {}

    public record TokenStats(
            int totalCalls,
            int totalPromptTokens,
            int totalCompletionTokens,
            int totalTokens,
            double totalCostUsd,
            int cachedCount,
            int uncachedCount
    ) {}

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
