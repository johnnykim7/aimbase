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
        double costUsd
) {
    public enum FinishReason {
        END, TOOL_USE, MAX_TOKENS, ERROR
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
