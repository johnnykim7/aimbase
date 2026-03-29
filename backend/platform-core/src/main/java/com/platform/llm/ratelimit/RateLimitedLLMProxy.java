package com.platform.llm.ratelimit;

import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate Limit 방어 프록시 (CR-013).
 *
 * LLMAdapter 호출 전에 프로바이더별 TokenBucket으로 RPM을 제어한다.
 * 한도 초과 시 maxWaitMs 동안 대기 후 재시도, 초과하면 429 에러.
 *
 * 사용법: OrchestratorEngine에서 adapter.chat() 대신 proxy.chat(adapter, request) 호출.
 */
@Component
public class RateLimitedLLMProxy {

    private static final Logger log = LoggerFactory.getLogger(RateLimitedLLMProxy.class);

    /** 프로바이더별 기본 RPM (설정 오버라이드 가능) */
    private static final Map<String, Integer> DEFAULT_RPM = Map.of(
            "anthropic", 50,
            "openai", 60,
            "ollama", 999  // 로컬 모델은 사실상 무제한
    );

    private static final long MAX_WAIT_MS = 30_000L; // 최대 30초 대기

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Value("${rate-limit.anthropic-rpm:50}")
    private int anthropicRpm;

    @Value("${rate-limit.openai-rpm:60}")
    private int openaiRpm;

    /**
     * Rate limit을 적용하여 LLM을 호출한다.
     */
    public CompletableFuture<LLMResponse> chat(LLMAdapter adapter, LLMRequest request) {
        String provider = adapter.getProvider();
        TokenBucket bucket = getBucket(provider);

        try {
            if (!bucket.tryAcquire(MAX_WAIT_MS)) {
                long waitMs = bucket.estimatedWaitMs();
                log.warn("Rate limit 초과: provider={}, 예상 대기 {}ms → 429 반환", provider, waitMs);
                return CompletableFuture.failedFuture(
                        new RateLimitExceededException(provider, waitMs));
            }

            log.debug("Rate limit 통과: provider={}, remaining={}/{}",
                    provider, bucket.getAvailableTokens(), bucket.getMaxTokens());
            return adapter.chat(request);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
    }

    /** 프로바이더별 현재 상태 조회 (모니터링용) */
    public Map<String, Object> getStatus(String provider) {
        TokenBucket bucket = buckets.get(provider);
        if (bucket == null) return Map.of("provider", provider, "status", "no_bucket");
        return Map.of(
                "provider", provider,
                "max_rpm", bucket.getMaxTokens(),
                "available", bucket.getAvailableTokens(),
                "estimated_wait_ms", bucket.estimatedWaitMs()
        );
    }

    private TokenBucket getBucket(String provider) {
        return buckets.computeIfAbsent(provider, p -> {
            int rpm = switch (p) {
                case "anthropic" -> anthropicRpm;
                case "openai" -> openaiRpm;
                default -> DEFAULT_RPM.getOrDefault(p, 60);
            };
            log.info("TokenBucket 생성: provider={}, rpm={}", p, rpm);
            return new TokenBucket(rpm);
        });
    }

    /** Rate limit 초과 예외 */
    public static class RateLimitExceededException extends RuntimeException {
        private final String provider;
        private final long estimatedWaitMs;

        public RateLimitExceededException(String provider, long estimatedWaitMs) {
            super("Rate limit exceeded for provider: " + provider
                    + " (estimated wait: " + estimatedWaitMs + "ms)");
            this.provider = provider;
            this.estimatedWaitMs = estimatedWaitMs;
        }

        public String getProvider() { return provider; }
        public long getEstimatedWaitMs() { return estimatedWaitMs; }
    }
}
