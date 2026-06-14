package com.platform.llm.model;

import java.util.List;
import java.util.Map;

/**
 * LLM 스트림 청크.
 * CR-030: thinking 델타를 type="thinking"으로 구분.
 * CR-045 Phase 2-B: done=true 청크에 finishReason + toolUses 노출 (도구 루프 스트리밍 통합).
 * CR-108: CLI 어댑터 내부 도구 루프 관찰을 turn 도중에 실시간 운반 —
 *   type="tool_use" / "tool_result" 청크. observedTool 필드에 단건 관찰을 싣는다.
 */
public record LLMStreamChunk(
        String id,
        String model,
        String delta,
        boolean done,
        TokenUsage usage,
        String type,
        LLMResponse.FinishReason finishReason,
        List<ToolCall> toolUses,
        ObservedTool observedTool
) {
    /** 하위 호환: 8-파라미터 생성자 (CR-045 Phase 2-B) */
    public LLMStreamChunk(String id, String model, String delta, boolean done,
                          TokenUsage usage, String type,
                          LLMResponse.FinishReason finishReason, List<ToolCall> toolUses) {
        this(id, model, delta, done, usage, type, finishReason, toolUses, null);
    }

    /** 하위 호환: 6-파라미터 생성자 (CR-030) */
    public LLMStreamChunk(String id, String model, String delta, boolean done,
                          TokenUsage usage, String type) {
        this(id, model, delta, done, usage, type, null, null, null);
    }

    /** 하위 호환: 5-파라미터 생성자 */
    public LLMStreamChunk(String id, String model, String delta, boolean done, TokenUsage usage) {
        this(id, model, delta, done, usage, delta != null ? "text" : null, null, null, null);
    }

    public static LLMStreamChunk text(String id, String model, String delta) {
        return new LLMStreamChunk(id, model, delta, false, null, "text", null, null, null);
    }

    /** CR-030: Extended Thinking 스트리밍 청크 */
    public static LLMStreamChunk thinking(String id, String model, String delta) {
        return new LLMStreamChunk(id, model, delta, false, null, "thinking", null, null, null);
    }

    public static LLMStreamChunk done(String id, String model, TokenUsage usage) {
        return new LLMStreamChunk(id, model, null, true, usage, null, null, null, null);
    }

    /** CR-045 Phase 2-B: finishReason + toolUses 포함 완료 청크 */
    public static LLMStreamChunk done(String id, String model, TokenUsage usage,
                                      LLMResponse.FinishReason finishReason,
                                      List<ToolCall> toolUses) {
        return new LLMStreamChunk(id, model, null, true, usage, null, finishReason, toolUses, null);
    }

    /**
     * CR-108: CLI 가 내부에서 호출한 도구를 관찰한 시점에 발행 (tool_use 도착 즉시).
     * output 은 아직 미수신이므로 null — 페어링은 소비측이 toolUseId 로 한다.
     */
    public static LLMStreamChunk observedToolUse(String id, String model, ObservedTool observed) {
        return new LLMStreamChunk(id, model, null, false, null, "tool_use", null, null, observed);
    }

    /** CR-108: 위 tool_use 의 결과(tool_result) 도착 즉시 발행 — output + durationMs 채워짐. */
    public static LLMStreamChunk observedToolResult(String id, String model, ObservedTool observed) {
        return new LLMStreamChunk(id, model, null, false, null, "tool_result", null, null, observed);
    }

    /**
     * CR-108: 실시간 관찰 단건. CLI 가 보낸 그대로의 tool_use / tool_result.
     *
     * @param toolUseId  CLI 의 tool_use id — tool_use ↔ tool_result 페어링 키
     * @param toolName   도구 이름 (예: mcp__aimbase-server__web_search)
     * @param input      도구 input (tool_use 시점에만, tool_result 시 null 가능)
     * @param output     도구 결과 본문 (tool_result 시점에만)
     * @param durationMs tool_use → tool_result 사이 근사 시간 (tool_result 시점에만)
     */
    public record ObservedTool(
            String toolUseId,
            String toolName,
            Map<String, Object> input,
            String output,
            Long durationMs
    ) {}
}
