package com.platform.llm.router;

import com.platform.config.PlatformSettingsService;
import com.platform.llm.ConnectionAdapterFactory;
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

    /** 복잡도별 기본 모델 매핑 */
    private static final Map<Complexity, String> COMPLEXITY_MODEL_MAP = Map.of(
            Complexity.SIMPLE, "anthropic/claude-haiku-4-5",
            Complexity.MODERATE, "anthropic/claude-sonnet-4-5",
            Complexity.COMPLEX, "anthropic/claude-opus-4-5"
    );

    private final LLMAdapterRegistry registry;
    private final IntentClassifier intentClassifier;
    private final PlatformSettingsService platformSettings;

    public ModelRouter(LLMAdapterRegistry registry, IntentClassifier intentClassifier,
                       PlatformSettingsService platformSettings) {
        this.registry = registry;
        this.intentClassifier = intentClassifier;
        this.platformSettings = platformSettings;
    }

    /**
     * CR-175 BIZ-150: 연결도 모델도 정해지지 않았을 때 쓰는 모델. 위젯 설정 조회가 알려 주는 기본 모델과
     * 실제로 답하는 모델이 같아야 하므로 같은 런타임 설정을 읽는다.
     */
    private String defaultModel() {
        String model = platformSettings.getString(ConnectionAdapterFactory.DEFAULT_MODEL_SETTING_KEY, null);
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("runtime setting "
                    + ConnectionAdapterFactory.DEFAULT_MODEL_SETTING_KEY + " is empty");
        }
        return model;
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
     * model 문자열을 정규화한다. 비었거나 "auto" 면 런타임 설정 llm.default-model 이다.
     * 대화 요청은 이 값을 모델로 보내므로 실제로 답하는 모델도 이것이다.
     */
    public String resolveModelId(String model) {
        if (model == null || model.isBlank() || "auto".equalsIgnoreCase(model)) {
            return defaultModel();
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
        String targetModel = COMPLEXITY_MODEL_MAP.get(complexity);

        // 대상 모델의 어댑터가 없으면 기본 모델로 폴백
        if (targetModel == null || !registry.hasAdapter(targetModel)) {
            log.warn("Smart routing 대상 모델 없음: {} → 기본 모델 사용", targetModel);
            targetModel = defaultModel();
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
