package com.yunzhu.video_data_analysis.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 双层语义缓存：
 * <ol>
 *   <li><b>ANN 召回</b> — {@link VectorStore} 向量相似度搜索（召回 top-1）</li>
 *   <li><b>LLM Judge</b> — cheap model 判断缓存问题的意图是否与当前问题一致</li>
 * </ol>
 * 只有两层都通过才命中缓存，避免"美食类视频有哪些"和"美食视频互动数据"这类
 * embedding 相似但意图不同的查询相互污染。
 */
@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private static final String DOC_TYPE = "cache";
    private static final String META_RESPONSE = "response";

    private static final String JUDGE_PROMPT = """
            判断两个问题是否在问同一个信息需求。输出 YES 或 NO。

            缓存的问题: {cached}
            当前的问题: {current}

            判断标准：
            - 两者都在问同一个指标（如播放量、完播率）、同一组实体（如相同分类）→ YES
            - 一个在问"有哪些"、另一个在问"数据怎么样" → NO（意图不同）
            - 一个在问"趋势"、另一个在问"对比" → NO（分析维度不同）
            - 只是提到了同一个实体（如美食），但问的问题完全不同 → NO

            只输出 YES 或 NO。
            """;

    private final VectorStore vectorStore;
    private final double threshold;
    private final ChatClient judge;
    private final AtomicInteger cacheSize = new AtomicInteger(0);

    public SemanticCacheService(VectorStore vectorStore,
                                @Value("${app.cache.similarity-threshold:0.92}") double threshold,
                                @Qualifier("cheapChatModel") ChatModel cheapChatModel) {
        this.vectorStore = vectorStore;
        this.threshold = threshold;
        this.judge = ChatClient.builder(cheapChatModel)
                .defaultSystem("你是一个缓存命中判定专家。判断两个问题是否在问同一件事。")
                .build();
    }

    /**
     * 双层查询：ANN 召回 → LLM Judge 意图匹配。
     *
     * @return 缓存命中则返回缓存的响应，否则返回 null
     */
    public String get(String query) {
        // 第一层：ANN 向量召回
        var results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(1)
                        .similarityThreshold(threshold)
                        .filterExpression("doc_type == '" + DOC_TYPE + "'")
                        .build());

        if (results.isEmpty()) {
            log.debug("Cache MISS (ANN) | query=\"{}\"", truncate(query, 40));
            return null;
        }

        Document best = results.get(0);
        String cachedQuery = best.getText();

        // 第二层：LLM Judge 意图匹配
        String verdict = judge.prompt()
                .user(u -> u.text(JUDGE_PROMPT)
                        .param("cached", cachedQuery)
                        .param("current", query))
                .call()
                .content();

        if (verdict == null || !verdict.trim().toUpperCase().startsWith("Y")) {
            log.info("Cache JUDGE REJECT | query=\"{}\" | cached=\"{}\" | verdict={}",
                    truncate(query, 40), truncate(cachedQuery, 40), verdict != null ? verdict.trim() : "null");
            return null;
        }

        log.info("Cache HIT | query=\"{}\" | cached=\"{}\" | score={} | judge=YES",
                truncate(query, 40), truncate(cachedQuery, 40), best.getScore());
        return best.getMetadata().get(META_RESPONSE).toString();
    }

    /**
     * 存储查询-响应对。
     */
    public void put(String query, String response) {
        Document doc = Document.builder()
                .text(query)
                .metadata(Map.of(META_RESPONSE, response, "doc_type", DOC_TYPE))
                .build();
        vectorStore.add(List.of(doc));
        cacheSize.incrementAndGet();
        log.debug("Cache PUT | query=\"{}\"", truncate(query, 40));
    }

    /** 近似的缓存条目数。 */
    public int size() {
        return cacheSize.get();
    }

    /** 重置内存计数器。 */
    public void clear() {
        cacheSize.set(0);
        log.info("Cache counter reset. Physical cleanup requires Redis FLUSHALL or FT.DROPINDEX.");
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
