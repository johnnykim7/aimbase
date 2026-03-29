package com.platform.llm.router;

import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.orchestrator.GenericCircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallback Chain 실행기 (PRD-122).
 *
 * 1차 모델 실패 시 fallback 모델 리스트를 순회하며 재시도.
 * 모델별 GenericCircuitBreaker로 장애 격리.
 * 지수 백오프: 1s → 2s → 4s.
 */
@Component
public class FallbackChainExecutor {

    private static final Logger log = LoggerFactory.getLogger(FallbackChainExecutor.class);

    private static final long BASE_DELAY_MS = 1000;
    private static final int CB_FAILURE_THRESHOLD = 3;
    private static final long CB_OPEN_DURATION_MS = 5 * 60 * 1000L;

    private final LLMAdapterRegistry registry;
    private final ConcurrentHashMap<String, GenericCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    public FallbackChainExecutor(LLMAdapterRegistry registry) {
        this.registry = registry;
    }

    /**
     * primary 어댑터로 먼저 시도, 실패 시 fallbackModels를 순회.
     */
    public LLMResponse execute(LLMRequest request, LLMAdapter primaryAdapter,
                                String primaryModel, List<String> fallbackModels) {
        // 1차: primary 시도
        GenericCircuitBreaker primaryCB = getCircuitBreaker(primaryModel);
        if (primaryCB.allowRequest()) {
            try {
                LLMResponse response = primaryAdapter.chat(request).get();
                primaryCB.recordSuccess();
                return response;
            } catch (Exception e) {
                primaryCB.recordFailure();
                log.warn("Primary model {} failed: {}", primaryModel, e.getMessage());
            }
        } else {
            log.info("Primary model {} circuit OPEN, skipping", primaryModel);
        }

        // 2차: fallback chain 순회
        if (fallbackModels == null || fallbackModels.isEmpty()) {
            throw new RuntimeException("LLM call failed: primary model " + primaryModel
                    + " failed and no fallback models configured");
        }

        Exception lastException = null;
        for (int i = 0; i < fallbackModels.size(); i++) {
            String fallbackModel = fallbackModels.get(i);
            GenericCircuitBreaker cb = getCircuitBreaker(fallbackModel);

            if (!cb.allowRequest()) {
                log.info("Fallback model {} circuit OPEN, skipping", fallbackModel);
                continue;
            }

            // 지수 백오프
            long delay = BASE_DELAY_MS * (1L << i); // 1s, 2s, 4s, ...
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Fallback interrupted", ie);
            }

            try {
                LLMAdapter fallbackAdapter = registry.getAdapter(fallbackModel);
                LLMRequest fallbackRequest = request.withModel(fallbackModel);
                LLMResponse response = fallbackAdapter.chat(fallbackRequest).get();
                cb.recordSuccess();
                log.info("Fallback model {} succeeded (attempt {})", fallbackModel, i + 1);
                return response;
            } catch (Exception e) {
                cb.recordFailure();
                lastException = e;
                log.warn("Fallback model {} failed (attempt {}): {}", fallbackModel, i + 1, e.getMessage());
            }
        }

        throw new RuntimeException("All models in fallback chain failed. Last error: "
                + (lastException != null ? lastException.getMessage() : "unknown"), lastException);
    }

    private GenericCircuitBreaker getCircuitBreaker(String model) {
        return circuitBreakers.computeIfAbsent(model,
                k -> new GenericCircuitBreaker(k, CB_FAILURE_THRESHOLD, CB_OPEN_DURATION_MS));
    }
}
