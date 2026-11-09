package com.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 키 없는 서버(운영 chaws)에서 임베딩이 만들어져 요청마다 OpenAI 401 이 나던 것을 막는다.
 */
class EmbeddingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EmbeddingConfig.class);

    @Test
    void 키가_빈_값이면_임베딩을_만들지_않는다() {
        runner.withPropertyValues("spring.ai.openai.api-key=")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(EmbeddingModel.class));
    }

    @Test
    void 키가_공백뿐이면_임베딩을_만들지_않는다() {
        runner.withPropertyValues("spring.ai.openai.api-key=   ")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(EmbeddingModel.class));
    }

    @Test
    void 키_설정이_없으면_임베딩을_만들지_않는다() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(EmbeddingModel.class));
    }

    @Test
    void 키가_있으면_임베딩을_만든다() {
        runner.withPropertyValues("spring.ai.openai.api-key=sk-test")
                .run(ctx -> assertThat(ctx).hasSingleBean(EmbeddingModel.class));
    }
}
