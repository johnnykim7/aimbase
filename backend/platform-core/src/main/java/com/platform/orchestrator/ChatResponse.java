package com.platform.orchestrator;

import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.TokenUsage;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatResponse(
        String id,
        String model,
        String sessionId,
        List<ContentBlock> content,
        List<Map<String, Object>> actionsExecuted,
        TokenUsage usage,
        double costUsd,
        Map<String, Object> guardrail
) {
    /** 가드레일 없는 기존 생성자 호환. */
    public ChatResponse(String id, String model, String sessionId,
                        List<ContentBlock> content,
                        List<Map<String, Object>> actionsExecuted,
                        TokenUsage usage, double costUsd) {
        this(id, model, sessionId, content, actionsExecuted, usage, costUsd, null);
    }
}
