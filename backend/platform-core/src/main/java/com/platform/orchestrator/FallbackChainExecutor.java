package com.platform.orchestrator;

import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallback Chain 실행기 (PRD-122).
 *
 * LLM 호출 실패 시 모델 A → B → C 자동 전환.
 * 지수 백오프(1s → 2s → 4s) 적용.
 * 서킷 브레이커 OPEN 상태인 모델은 skip.
 */
@Component
public class FallbackChainExecutor {

    private static final Logger log = LoggerFactory.getLogger(FallbackChainExecutor.class);

    private static final long BASE_BACKOFF_MS = 1000L;
    private static final int MAX_BACKOFF_EXPONENT = 3;

    private final LLMAdapterRegistry adapterRegistry;

    /** 모델별 서킷 브레이커 (모델 ID → GenericCircuitBreaker) */
    private final Map<String, GenericCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    public FallbackChainExecutor(LLMAdapterRegistry adapterRegistry) {
        this.adapterRegistry = adapterRegistry;
    }

    /**
     * 폴백 체인으로 LLM 호출을 시도한다.
     * primaryModel 실패 시 fallbackModels 순서대로 재시도.
     *
     * @param request        원본 요청
     * @param primaryModel   1차 모델 ID (예: "anthropic/claude-sonnet-4-5")
     * @param fallbackModels 대체 모델 ID 리스트
     * @return 성공한 모델의 응답
     * @throws RuntimeException 모든 모델 실패 시
     */
    public LLMResponse executeWithFallback(LLMRequest request, String primaryModel,
                                            List<String> fallbackModels) {
        // 전체 시도 모델 리스트: primary + fallbacks
        List<String> allModels = new java.util.ArrayList<>();
        allModels.add(primaryModel);
        if (fallbackModels != null) {
            allModels.addAll(fallbackModels);
        }

        Exception lastException = null;

        for (int i = 0; i < allModels.size(); i++) {
            String modelId = allModels.get(i);
            GenericCircuitBreaker cb = getCircuitBreaker(modelId);

            // 서킷 OPEN이면 skip
            if (!cb.allowRequest()) {
                log.info("서킷 OPEN, skip: model={}", modelId);
                continue;
            }

            try {
                // 지수 백오프 (첫 번째 모델은 즉시 호출)
                if (i > 0) {
                    long backoff = BASE_BACKOFF_MS * (1L << Math.min(i - 1, MAX_BACKOFF_EXPONENT));
                    log.debug("백오프 대기: {}ms (model={})", backoff, modelId);
                    Thread.sleep(backoff);
                }

                LLMAdapter adapter = adapterRegistry.getAdapter(modelId);
                LLMResponse response = adapter.chat(request).get();

                cb.recordSuccess();
                if (i > 0) {
                    log.info("폴백 성공: model={} ({}번째 시도)", modelId, i + 1);
                }
                return response;

            } catch (Exception e) {
                cb.recordFailure();
                lastException = e;
                log.warn("모델 호출 실패: model={}, error={}", modelId, e.getMessage());
            }
        }

        throw new RuntimeException("모든 모델 호출 실패 (시도: " + allModels + ")",
                lastException);
    }

    /** 모델별 서킷 브레이커를 가져오거나 생성 */
    GenericCircuitBreaker getCircuitBreaker(String modelId) {
        return circuitBreakers.computeIfAbsent(modelId,
                id -> new GenericCircuitBreaker("llm:" + id, 3, 5 * 60 * 1000L));
    }
}
