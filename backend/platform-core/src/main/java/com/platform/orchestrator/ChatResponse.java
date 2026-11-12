package com.platform.orchestrator;

import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.TokenUsage;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatResponse(
        String id,
        String model,
        String sessionId,
        List<ContentBlock> content,
        List<Map<String, Object>> actionsExecuted,
        TokenUsage usage,
        double costUsd,
        Map<String, Object> guardrail,
        @JsonProperty("citations")  List<Map<String, Object>> citations,
        @JsonProperty("rag_used")   Boolean ragUsed,
        @JsonProperty("turn_id")    String turnId,
        @JsonProperty("turn_seq")   Integer turnSeq
) {
    public ChatResponse(String id, String model, String sessionId,
                        List<ContentBlock> content,
                        List<Map<String, Object>> actionsExecuted,
                        TokenUsage usage, double costUsd,
                        Map<String, Object> guardrail,
                        List<Map<String, Object>> citations, Boolean ragUsed) {
        this(id, model, sessionId, content, actionsExecuted, usage, costUsd, guardrail, citations, ragUsed, null, null);
    }

    /** 가드레일 없는 기존 생성자 호환. */
    public ChatResponse(String id, String model, String sessionId,
                        List<ContentBlock> content,
                        List<Map<String, Object>> actionsExecuted,
                        TokenUsage usage, double costUsd) {
        this(id, model, sessionId, content, actionsExecuted, usage, costUsd, null, null, null);
    }

    /** CR-029 이후 guardrail까지 받는 기존 생성자 호환. */
    public ChatResponse(String id, String model, String sessionId,
                        List<ContentBlock> content,
                        List<Map<String, Object>> actionsExecuted,
                        TokenUsage usage, double costUsd,
                        Map<String, Object> guardrail) {
        this(id, model, sessionId, content, actionsExecuted, usage, costUsd, guardrail, null, null);
    }
}
