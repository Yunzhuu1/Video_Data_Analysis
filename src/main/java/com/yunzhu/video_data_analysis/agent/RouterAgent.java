package com.yunzhu.video_data_analysis.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 将用户问题路由到简单或复杂分析路径。
 * <p>
 * 使用<b>基于关键词的快捷方式</b>跳过LLM调用，对于明显
 * 简单的查询，每个简单请求节省约1次LLM调用。
 * 对于模糊情况，回退到廉价模型 (gpt-4o-mini)。
 */
@Component
public class RouterAgent {

    private static final Logger log = LoggerFactory.getLogger(RouterAgent.class);

    /**
     * 毫无疑问的简单查询关键词 — 纯数据列举，
     * 不涉及指标计算。可以安全地短路处理。
     */
    private static final Pattern LISTING_KEYWORDS = Pattern.compile(
            "(有哪些|列出|展示|给我看看|找一下)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 强烈暗示需要多步推理的<b>复杂</b>查询关键词。
     * 直接路由到复杂路径。
     */
    private static final Pattern COMPLEX_KEYWORDS = Pattern.compile(
            "(为什么|原因|对比|差异|趋势|变化|归因|分析|暴涨|暴跌|环比|同比)",
            Pattern.CASE_INSENSITIVE);

    private static final String CLASSIFICATION_PROMPT = """
            分析复杂度，输出 simple 或 complex。
            simple: 单指标/单数据查询，无对比无归因。例："昨天播放量多少"
            complex: 多步推理、对比、归因。例："为什么完播率下降了"
            只输出一个词。
            """;

    private final ChatClient chatClient;

    public RouterAgent(@Qualifier("cheapChatModel") ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(CLASSIFICATION_PROMPT)
                .build();
    }

    /** @return 如果问题应该使用简单路径则返回true */
    public boolean isSimple(String question) {
        // 快速路径：纯列举查询 → 毫无疑问的简单
        if (LISTING_KEYWORDS.matcher(question).find()) {
            log.info("RouterAgent | listing-shortcut | route=simple");
            return true;
        }

        // 快速路径：复杂关键词 → 毫无疑问的复杂
        if (COMPLEX_KEYWORDS.matcher(question).find()) {
            log.info("RouterAgent | complex-shortcut | route=complex");
            return false;
        }

        // 回退：模糊情况使用廉价模型
        String result = chatClient.prompt().user(question).call().content();
        boolean simple = result != null && result.trim().toLowerCase().startsWith("simple");
        log.info("RouterAgent | llm-fallback | route={}", simple ? "simple" : "complex");
        return simple;
    }
}
