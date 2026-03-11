package com.platform.llm.model;

public record TokenUsage(int inputTokens, int outputTokens) {

    public int total() {
        return inputTokens + outputTokens;
    }
}
