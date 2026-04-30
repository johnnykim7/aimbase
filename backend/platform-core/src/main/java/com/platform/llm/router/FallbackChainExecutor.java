package com.platform.llm.router;

import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.orchestrator.GenericCircuitBreaker;
import com.platform.service.AgentRegisteredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
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

    /**
     * CR-081: agent 가 새로 등록(또는 재등록)되면 누적된 모든 LLM 모델 CB 를 reset.
     *
     * <p>이전 세션 회고: agent 가 죽었다 살아났을 때 BE 의 CB 가 OPEN 상태로 남아 있어
     * 새 endpoint 로도 호출 시도조차 안 되는 케이스가 관찰됐다. 이로 인해 사용자가
     * BE 까지 재기동해야 회복되는 패턴이 발생.
     *
     * <p>agent 등록은 명시적인 회복 신호이므로, 이 이벤트로 모든 모델 CB 를 일괄 reset 한다.
     * CR-082 로 CLI provider 가 더 이상 fallback chain 에 진입하지 않으므로 anthropic-cli
     * 모델 CB 는 OPEN 되지 않지만, 다른 모델로의 운영 중 누적된 OPEN 도 함께 풀어준다 —
     * agent 부활은 인프라 회복의 신호로 보수적으로 가정.
     */
    @EventListener
    public void onAgentRegistered(AgentRegisteredEvent event) {
        if (circuitBreakers.isEmpty()) return;
        int reset = 0;
        for (GenericCircuitBreaker cb : circuitBreakers.values()) {
            if (cb.getState() != GenericCircuitBreaker.State.CLOSED) {
                cb.reset();
                reset++;
            }
        }
        if (reset > 0) {
            log.info("CR-081: agent 등록 이벤트로 OPEN/HALF_OPEN CB {}개 reset (agentId={}, userId={}, reregister={})",
                    reset, event.agentId(), event.userId(), event.wasReregister());
        }
    }
}
