package com.yunzhu.video_data_analysis.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 模型配置，采用异构策略：
 * <ul>
 *   <li><b>strongChatModel</b> (@Primary) — 复杂推理（SQL、洞察）</li>
 *   <li><b>cheapChatModel</b> (@Qualifier) — 分类、路由、建议</li>
 *   <li><b>localEmbeddingModel</b> — 通过Ollama的本地all-MiniLM-L6-v2，
 *       消除外部embedding API依赖和成本</li>
 * </ul>
 */
@Configuration
public class AgentModelConfig {

    @Primary
    @Bean("strongChatModel")
    public ChatModel strongChatModel(OpenAiApi openAiApi,
                                     @Value("${app.ai.strong-model:gpt-4o}") String model) {
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build();
    }

    @Bean("cheapChatModel")
    public ChatModel cheapChatModel(OpenAiApi openAiApi,
                                     @Value("${app.ai.cheap-model:gpt-4o-mini}") String model) {
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build();
    }

    /**
     * 通过Ollama的本地embedding模型 (all-MiniLM-L6-v2)。
     * 完全本地化，零API成本，零外部依赖。
     */
    @Bean
    @Primary
    public EmbeddingModel localEmbeddingModel(
            @Value("${app.embedding.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${app.embedding.ollama.model:nomic-embed-text}") String model) {
        OpenAiApi ollamaApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey("ollama") // Ollama doesn't require a real API key
                .build();
        return new OpenAiEmbeddingModel(ollamaApi, MetadataMode.NONE,
                OpenAiEmbeddingOptions.builder().model(model).build());
    }
}
