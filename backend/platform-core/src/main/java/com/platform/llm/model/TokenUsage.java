package com.platform.llm.model;

/**
 * LLM 호출 토큰 사용량.
 * Anthropic prompt cache 필드 + Extended Thinking 토큰 포함.
 */
public record TokenUsage(
        int inputTokens,
        int outputTokens,
        int cacheCreationInputTokens,   // 캐시 쓰기 (1.25x 과금)
        int cacheReadInputTokens,        // 캐시 읽기 (0.1x 과금)
        int thinkingInputTokens,         // CR-030: Extended Thinking 입력 토큰
        int thinkingOutputTokens         // CR-030: Extended Thinking 출력 토큰
) {

    /** 캐시 미사용 생성자 (하위 호환) */
    public TokenUsage(int inputTokens, int outputTokens) {
        this(inputTokens, outputTokens, 0, 0, 0, 0);
    }

    /** 캐시 포함, thinking 미사용 생성자 (하위 호환) */
    public TokenUsage(int inputTokens, int outputTokens,
                      int cacheCreationInputTokens, int cacheReadInputTokens) {
        this(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens, 0, 0);
    }

    public int total() {
        return inputTokens + outputTokens;
    }

    /** 실제 청구 대상 input 토큰 수 (캐시 읽기 제외) */
    public int billableInputTokens() {
        return inputTokens + cacheCreationInputTokens;
    }
}
