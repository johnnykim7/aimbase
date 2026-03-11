package com.platform.orchestrator;

import com.platform.llm.model.UnifiedMessage;

import java.util.List;

public record ChatRequest(
        String model,
        String sessionId,
        List<UnifiedMessage> messages,
        boolean stream,
        boolean actionsEnabled,
        String userId,
        String ragSourceId,
        String connectionId
) {
    public ChatRequest(String model, List<UnifiedMessage> messages) {
        this(model, null, messages, false, false, null, null, null);
    }
}
