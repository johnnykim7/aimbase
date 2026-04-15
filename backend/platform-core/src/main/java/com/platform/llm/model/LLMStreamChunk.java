package com.platform.llm.model;

import java.util.List;

/**
 * LLM 스트림 청크.
 * CR-030: thinking 델타를 type="thinking"으로 구분.
 * CR-045 Phase 2-B: done=true 청크에 finishReason + toolUses 노출 (도구 루프 스트리밍 통합).
 */
public record LLMStreamChunk(
        String id,
        String model,
        String delta,
        boolean done,
        TokenUsage usage,
        String type,
        LLMResponse.FinishReason finishReason,
        List<ToolCall> toolUses
) {
    /** 하위 호환: 6-파라미터 생성자 (CR-030) */
    public LLMStreamChunk(String id, String model, String delta, boolean done,
                          TokenUsage usage, String type) {
        this(id, model, delta, done, usage, type, null, null);
    }

    /** 하위 호환: 5-파라미터 생성자 */
    public LLMStreamChunk(String id, String model, String delta, boolean done, TokenUsage usage) {
        this(id, model, delta, done, usage, delta != null ? "text" : null, null, null);
    }

    public static LLMStreamChunk text(String id, String model, String delta) {
        return new LLMStreamChunk(id, model, delta, false, null, "text", null, null);
    }

    /** CR-030: Extended Thinking 스트리밍 청크 */
    public static LLMStreamChunk thinking(String id, String model, String delta) {
        return new LLMStreamChunk(id, model, delta, false, null, "thinking", null, null);
    }

    public static LLMStreamChunk done(String id, String model, TokenUsage usage) {
        return new LLMStreamChunk(id, model, null, true, usage, null, null, null);
    }

    /** CR-045 Phase 2-B: finishReason + toolUses 포함 완료 청크 */
    public static LLMStreamChunk done(String id, String model, TokenUsage usage,
                                      LLMResponse.FinishReason finishReason,
                                      List<ToolCall> toolUses) {
        return new LLMStreamChunk(id, model, null, true, usage, null, finishReason, toolUses);
    }
}
