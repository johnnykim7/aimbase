package com.platform.config;

import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * OpenAI Embedding 수동 설정.
 * spring.ai.openai.api-key 에 값이 있을 때만 EmbeddingModel 빈을 생성한다.
 * 키가 없으면 RAG는 MCP 사이드카를 통해서만 동작한다.
 */
@Configuration
@Conditional(EmbeddingConfig.OpenAiKeyPresent.class)
public class EmbeddingConfig {

    @Bean
    public OpenAiApi openAiApi(@Value("${spring.ai.openai.api-key}") String apiKey) {
        return OpenAiApi.builder().apiKey(apiKey).build();
    }

    @Bean
    public OpenAiEmbeddingModel openAiEmbeddingModel(OpenAiApi openAiApi,
            @Value("${spring.ai.openai.embedding.options.model:text-embedding-3-small}") String model,
            @Value("${spring.ai.openai.embedding.options.dimensions:1536}") int dimensions) {
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(model)
                .dimensions(dimensions)
                .build();
        return new OpenAiEmbeddingModel(openAiApi, org.springframework.ai.document.MetadataMode.EMBED, options);
    }

    /**
     * {@code @ConditionalOnProperty} 는 빈 문자열도 참으로 본다. 키 없는 서버에서는
     * {@code ${OPENAI_API_KEY:}} 가 빈 값이 되어, 키 없는 임베딩이 만들어지고 부를 때마다 401 이 났다.
     */
    static class OpenAiKeyPresent implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment().getProperty("spring.ai.openai.api-key"));
        }
    }
}
