package com.platform.llm.router;

import com.platform.llm.LLMAdapterRegistry;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.LLMRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 모델 라우터.
 * Phase 1: model 문자열 기반 단순 라우팅
 * Phase 4: DB의 routing_config를 참고하는 Smart Routing (비용/성능/intent 기반)으로 확장
 */
@Component
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);
    private static final String DEFAULT_MODEL = "anthropic/claude-sonnet-4-5";

    private final LLMAdapterRegistry registry;

    public ModelRouter(LLMAdapterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 요청에서 사용할 어댑터를 선택한다.
     * model이 "auto"면 기본 모델로 라우팅.
     */
    public LLMAdapter route(LLMRequest request) {
        String model = request.model();

        if (model == null || model.isBlank() || "auto".equalsIgnoreCase(model)) {
            log.debug("Model is '{}', routing to default: {}", model, DEFAULT_MODEL);
            return registry.getAdapter(DEFAULT_MODEL);
        }

        log.debug("Routing model: {}", model);
        return registry.getAdapter(model);
    }

    /**
     * model 문자열을 정규화한다.
     * "auto" → 기본 모델 ID 반환
     */
    public String resolveModelId(String model) {
        if (model == null || model.isBlank() || "auto".equalsIgnoreCase(model)) {
            return DEFAULT_MODEL;
        }
        return model;
    }
}
