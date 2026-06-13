package com.platform.llm.model;

import java.util.List;

public record LLMResponse(
        String id,
        String model,
        List<ContentBlock> content,
        List<ToolCall> toolCalls,
        TokenUsage usage,
        FinishReason finishReason,
        long latencyMs,
        double costUsd,
        List<ObservedToolEvent> observedToolEvents
) {
    public enum FinishReason {
        END, TOOL_USE, MAX_TOKENS, ERROR
    }

    /** 기존 8-arg 호환 (CR-102 관찰 이벤트 없음) — 대부분의 어댑터/테스트 호출부용 */
    public LLMResponse(String id, String model, List<ContentBlock> content,
                       List<ToolCall> toolCalls, TokenUsage usage,
                       FinishReason finishReason, long latencyMs, double costUsd) {
        this(id, model, content, toolCalls, usage, finishReason, latencyMs, costUsd, null);
    }

    /** CR-102: CLI 내부 도구 루프 관찰 이벤트 존재 여부 (가시화 전용 — 도구 루프 재진입과 무관) */
    public boolean hasObservedToolEvents() {
        return observedToolEvents != null && !observedToolEvents.isEmpty();
    }

    public String textContent() {
        return content.stream()
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .reduce("", (a, b) -> a + b);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /** CR-030: thinking 블록만 추출 */
    public List<ContentBlock.Thinking> thinkingBlocks() {
        return content.stream()
                .filter(b -> b instanceof ContentBlock.Thinking)
                .map(b -> (ContentBlock.Thinking) b)
                .toList();
    }
}
