package com.platform.llm.model;

public record LLMStreamChunk(
        String id,
        String model,
        String delta,
        boolean done,
        TokenUsage usage
) {
    public static LLMStreamChunk text(String id, String model, String delta) {
        return new LLMStreamChunk(id, model, delta, false, null);
    }

    public static LLMStreamChunk done(String id, String model, TokenUsage usage) {
        return new LLMStreamChunk(id, model, null, true, usage);
    }
}
