package com.platform.llm.router;

import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.LLMRequest;
import com.platform.orchestrator.IntentClassifier;
import com.platform.orchestrator.IntentClassifier.Complexity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 모델 라우터 (PRD-125 확장).
 *
 * Phase 1: model 문자열 기반 단순 라우팅
 * Phase 2 (PRD-125): model="auto"일 때 IntentClassifier 결과에 따라 최적 모델 자동 선택
 *   SIMPLE → Haiku ($0.25/1M)
 *   MODERATE → Sonnet ($3/1M)
 *   COMPLEX → Opus ($15/1M)
 */
@Component
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);
    private static final String DEFAULT_MODEL = "anthropic/claude-sonnet-4-5";

    /** 복잡도별 기본 모델 매핑 */
    private static final Map<Complexity, String> COMPLEXITY_MODEL_MAP = Map.of(
            Complexity.SIMPLE, "anthropic/claude-haiku-4-5",
            Complexity.MODERATE, "anthropic/claude-sonnet-4-5",
            Complexity.COMPLEX, "anthropic/claude-opus-4-5"
    );

    private final LLMAdapterRegistry registry;
    private final IntentClassifier intentClassifier;

    public ModelRouter(LLMAdapterRegistry registry, IntentClassifier intentClassifier) {
        this.registry = registry;
        this.intentClassifier = intentClassifier;
    }

    /**
     * 요청에서 사용할 어댑터를 선택한다.
     * model이 "auto"면 IntentClassifier로 스마트 라우팅.
     */
    public LLMAdapter route(LLMRequest request) {
        String model = request.model();

        if (model == null || model.isBlank() || "auto".equalsIgnoreCase(model)) {
            return routeByIntent(request);
        }

        log.debug("Routing model: {}", model);
        return registry.getAdapter(model);
    }

    /**
     * model 문자열을 정규화한다.
     * "auto" → IntentClassifier 기반 모델 ID 반환
     */
    /**
     * model 문자열을 정규화한다.
     * "auto" → IntentClassifier 기반 모델 ID 반환 (Smart Routing 반영).
     */
    public String resolveModelId(String model) {
        if (model == null || model.isBlank() || "auto".equalsIgnoreCase(model)) {
            return DEFAULT_MODEL; // 실제 라우팅은 route()에서 IntentClassifier로 결정
        }
        return model;
    }

    /** 활성 routing_config에서 fallback chain을 조회한다. */
    public java.util.List<String> getFallbackChain() {
        // RoutingConfigRepository는 향후 DI로 주입. 현재는 기본 fallback 반환.
        return java.util.List.of(
                "anthropic/claude-sonnet-4-5",
                "anthropic/claude-haiku-4-5-20251001"
        );
    }

    /**
     * 의도 기반 모델 라우팅 (PRD-125).
     * 요청의 마지막 사용자 메시지에서 복잡도를 분류하여 모델을 선택한다.
     */
    private LLMAdapter routeByIntent(LLMRequest request) {
        String userMessage = extractLastUserMessage(request);
        Complexity complexity = intentClassifier.classify(userMessage);
        String targetModel = COMPLEXITY_MODEL_MAP.getOrDefault(complexity, DEFAULT_MODEL);

        // 대상 모델의 어댑터가 없으면 기본 모델로 폴백
        if (!registry.hasAdapter(targetModel)) {
            log.warn("Smart routing 대상 모델 없음: {} → 기본 모델 사용", targetModel);
            targetModel = DEFAULT_MODEL;
        }

        log.info("Smart routing: complexity={}, model={}", complexity, targetModel);
        return registry.getAdapter(targetModel);
    }

    private String extractLastUserMessage(LLMRequest request) {
        if (request.messages() == null || request.messages().isEmpty()) {
            return "";
        }
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            var msg = request.messages().get(i);
            if (msg.role() == com.platform.llm.model.UnifiedMessage.Role.USER) {
                return msg.content().stream()
                        .filter(cb -> cb instanceof com.platform.llm.model.ContentBlock.Text)
                        .map(cb -> ((com.platform.llm.model.ContentBlock.Text) cb).text())
                        .findFirst().orElse("");
            }
        }
        return "";
    }
}
