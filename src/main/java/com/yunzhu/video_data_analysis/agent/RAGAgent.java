package com.yunzhu.video_data_analysis.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunzhu.video_data_analysis.dto.CommentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG智能体，采用三阶段管道进行高质量的评论检索：
 * <ol>
 *   <li><b>查询重写</b> — 将指标问题（“完播率为什么下降”）
 *       桥接到体验搜索词（“广告 卡顿 画质 差评”）</li>
 *   <li><b>向量搜索</b> — Redis VectorStore top-10 → <b>LLM重排序</b> → top-5</li>
 *   <li><b>自我反思</b> — 检查检索到的评论是否真正解释了
 *       指标变化，然后再注入到 InsightAgent</li>
 * </ol>
 * 所有三个阶段都使用廉价模型 (gpt-4o-mini)。
 */
@Component
public class RAGAgent {

    private static final Logger log = LoggerFactory.getLogger(RAGAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DOC_TYPE = "comment";
    private static final int VECTOR_TOP_K = 10;
    private static final int RERANK_TOP_K = 5;

    /* ==================== 提示词 ==================== */

    private static final String REWRITE_PROMPT = """
            指标→评论搜索关键词。体验问题(广告/卡顿/画质/内容)。
            只输入关键词空格分隔，勿解释。
            原问题:{question}
            数据:{data}
            """;

    private static final String RERANK_PROMPT = """
            评论与问题相关度 0-10。10=直接解释数据变化原因。
            问题:{question} 数据:{data}
            评论:{comment}
            只输出数字。
            """;

    private static final String REFLECT_PROMPT = """
            评论主题能否解释问题？能→true 否→false
            问题:{question} 主题:{themes} 负面占比:{negativeRatio}
            只输出true/false。
            """;

    private static final String THEME_EXTRACTION_PROMPT = """
            提取评论主题词3-5个，评估负面占比。
            输出JSON:{"themes":["主题1","主题2"],"negativeRatio":0.7,"summary":"总结"}
            上下文:{context} 评论:{comments}
            只输出JSON。
            """;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public RAGAgent(@Qualifier("cheapChatModel") ChatModel chatModel,
                    VectorStore vectorStore) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("你是一个用户评论分析师。")
                .build();
        this.vectorStore = vectorStore;
    }

    /**
     * 三阶段RAG管道：
     * <ol>
     *   <li>为评论搜索重写查询</li>
     *   <li>Redis VectorStore向量搜索 → LLM重排序</li>
     *   <li>自我反思 → 返回结果或空</li>
     * </ol>
     */
    public CommentResult analyze(String question, String queryResult) {
        log.info("RAGAgent: stage 1/3 — rewriting query for comment search");

        // 阶段1：查询重写
        String searchQuery = rewriteQuery(question, queryResult);
        log.info("RAGAgent: rewritten query=\"{}\"", searchQuery);

        // 阶段2：Redis VectorStore搜索 + LLM重排序
        log.info("RAGAgent: stage 2/3 — Redis VectorStore search (top-{}) + reranker", VECTOR_TOP_K);
        List<Document> candidates = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(searchQuery)
                        .topK(VECTOR_TOP_K)
                        .filterExpression("doc_type == '" + DOC_TYPE + "'")
                        .build());

        if (candidates.isEmpty()) {
            log.info("RAGAgent: no candidates from Redis VectorStore");
            return emptyResult();
        }

        List<Document> topComments = rerank(question, queryResult, candidates);
        log.info("RAGAgent: reranked top-{} from {} candidates", topComments.size(), candidates.size());

        // 阶段3：自我反思（软信号——不再拦截结果，而是给出可信度）
        log.info("RAGAgent: stage 3/3 — self-reflection (soft gate)");
        CommentResult result = extractThemes(question, queryResult, topComments);

        double confidence = measureConfidence(question, result);
        result.setConfidence(confidence);

        long negativeCount = topComments.stream()
                .filter(d -> "negative".equals(d.getMetadata().get("sentiment")))
                .count();
        result.setTotalScanned(topComments.size());
        result.setNegativeRatio(topComments.isEmpty() ? 0 : (double) negativeCount / topComments.size());
        result.setRepresentativeComments(
                topComments.stream()
                        .filter(d -> "negative".equals(d.getMetadata().get("sentiment")))
                        .limit(3)
                        .map(Document::getText)
                        .collect(Collectors.toList()));

        log.info("RAGAgent complete | themes={} | negativeRatio={} | confidence={}",
                result.getThemes(), result.getNegativeRatio(), String.format("%.2f", confidence));
        return result;
    }

    /* ==================== 阶段1：查询重写 ==================== */

    private String rewriteQuery(String question, String queryResult) {
        String dataPreview = truncate(queryResult, 150);
        String rewritten = chatClient.prompt()
                .user(u -> u.text(REWRITE_PROMPT)
                        .param("question", question)
                        .param("data", dataPreview))
                .call()
                .content();
        if (rewritten == null || rewritten.trim().isEmpty()) return question;
        return rewritten.trim();
    }

    /* ==================== 阶段2：LLM 重排序（10 路并发） ==================== */

    private List<Document> rerank(String question, String queryResult, List<Document> candidates) {
        List<java.util.concurrent.CompletableFuture<ScoredDoc>> futures = candidates.stream()
                .map(doc -> java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> new ScoredDoc(doc, scoreComment(question, queryResult, doc))))
                .collect(Collectors.toList());

        return futures.stream()
                .map(java.util.concurrent.CompletableFuture::join)
                .filter(sd -> sd.score >= 5)
                .sorted(java.util.Comparator.<ScoredDoc>comparingInt(sd -> sd.score).reversed())
                .limit(RERANK_TOP_K)
                .map(sd -> sd.doc)
                .collect(Collectors.toList());
    }

    private int scoreComment(String question, String queryResult, Document doc) {
        try {
            String scoreStr = chatClient.prompt()
                    .user(u -> u.text(RERANK_PROMPT)
                            .param("question", question)
                            .param("data", truncate(queryResult, 150))
                            .param("comment", doc.getText()))
                    .call()
                    .content();
            if (scoreStr == null) return 0;
            return Integer.parseInt(scoreStr.trim().replaceAll("\\D", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private record ScoredDoc(Document doc, int score) {}

    /* ==================== 阶段3：自我反思（软信号） ==================== */

    /**
     * 衡量评论证据的可信度 0.0-1.0。
     * 不再做硬拦截（false = 丢弃结果），而是由 InsightAgent 根据置信度自行判断。
     */
    private double measureConfidence(String question, CommentResult result) {
        if (result.getThemes() == null || result.getThemes().isEmpty()) return 0.0;
        try {
            String answer = chatClient.prompt()
                    .user(u -> u.text(REFLECT_PROMPT)
                            .param("question", question)
                            .param("themes", String.join(", ", result.getThemes()))
                            .param("negativeRatio", String.format("%.2f", result.getNegativeRatio())))
                    .call()
                    .content();
            boolean pass = answer != null && answer.trim().toLowerCase().contains("true");
            return pass ? 0.8 : 0.3; // 通过=0.8, 不通过=0.3（仍保留证据给下游判断）
        } catch (Exception e) {
            return 0.5; // 容错：未知时给中等可信度
        }
    }

    /* ==================== 主题提取 ==================== */

    private CommentResult extractThemes(String question, String queryResult, List<Document> comments) {
        String commentText = comments.stream()
                .map(d -> String.format("[%s | %s] %s",
                        d.getMetadata().getOrDefault("contentId", "?"),
                        d.getMetadata().getOrDefault("sentiment", "?"),
                        d.getText()))
                .collect(Collectors.joining("\n"));

        String context = "用户问题: " + question + "\n分析上下文: " + truncate(queryResult, 200);

        String json = chatClient.prompt()
                .user(u -> u.text(THEME_EXTRACTION_PROMPT)
                        .param("context", context)
                        .param("comments", commentText))
                .call()
                .content();

        if (json == null || json.trim().isEmpty()) return emptyResult();

        String cleaned = json.trim().replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

        try {
            return MAPPER.readValue(cleaned, CommentResult.class);
        } catch (Exception e) {
            log.warn("Failed to parse themes: {}", e.getMessage());
            return emptyResult();
        }
    }

    private static CommentResult emptyResult() {
        CommentResult r = new CommentResult(List.of(), 0, 0.0, List.of(), "暂无评论数据");
        r.setConfidence(0.0);
        return r;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
