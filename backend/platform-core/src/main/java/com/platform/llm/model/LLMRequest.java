package com.platform.llm.model;

import java.util.List;

public record LLMRequest(
        String model,
        List<UnifiedMessage> messages,
        List<UnifiedToolDef> tools,
        ModelConfig config,
        boolean stream,
        String sessionId
) {
    public LLMRequest(String model, List<UnifiedMessage> messages) {
        this(model, messages, null, ModelConfig.defaults(), false, null);
    }
}
