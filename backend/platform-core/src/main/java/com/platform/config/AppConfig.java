package com.platform.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.platform.tenant.TenantContext;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableCaching
public class AppConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * LLMAdapterRegistry용 기본 클라이언트 빈.
     * 실제 채팅은 ConnectionAdapterFactory에서 Connection별 API Key로 클라이언트를 생성.
     */
    @Bean
    public AnthropicClient anthropicClient() {
        return AnthropicOkHttpClient.builder()
                .apiKey("placeholder")
                .build();
    }

    @Bean
    public OpenAIClient openAIClient() {
        return OpenAIOkHttpClient.builder()
                .apiKey("placeholder")
                .build();
    }

    /**
     * @Async 전용 실행기.
     *
     * TenantContext 는 ThreadLocal 이라 @Async 로 넘어가는 순간 사라진다. 그러면 테넌트 라우팅
     * DataSource 가 master 로 폴백하는데, master DB 에는 테넌트 테이블이 없다 —
     * AuditLogger.logLLMCall 이 매 LLM 호출마다 `relation "audit_logs" does not exist` 로
     * 죽어 감사로그가 전량 유실되고 있었다(WARN 으로 삼켜져 기능은 안 죽는다).
     * 호출자마다 테넌트를 넘기게 고치면 @Async 를 쓰는 모든 곳에 같은 실수가 반복되므로,
     * 실행기 한 곳에서 제출 시점의 테넌트를 복사해 넣는다.
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("platform-");
        executor.setTaskDecorator(task -> {
            String tenantId = TenantContext.getTenantId();
            return () -> {
                if (tenantId == null) {
                    task.run();
                    return;
                }
                String previous = TenantContext.getTenantId();
                TenantContext.setTenantId(tenantId);
                try {
                    task.run();
                } finally {
                    // 풀 스레드는 재사용된다 — 반드시 원복해야 다음 작업이 남은 테넌트를 물려받지 않는다.
                    if (previous != null) {
                        TenantContext.setTenantId(previous);
                    } else {
                        TenantContext.clear();
                    }
                }
            };
        });
        executor.initialize();
        return executor;
    }
}
