package com.platform.llm.model;

import java.util.List;

public record ModelConfig(
        Double temperature,
        Integer maxTokens,
        Double topP,
        List<String> stopSequences,
        Boolean extendedThinking,        // CR-030: Extended Thinking 활성화
        Integer thinkingBudgetTokens     // CR-030: thinking 토큰 예산 (≥1024, < maxTokens)
) {
    /** 하위 호환: 기존 4-파라미터 생성자 */
    public ModelConfig(Double temperature, Integer maxTokens, Double topP, List<String> stopSequences) {
        this(temperature, maxTokens, topP, stopSequences, null, null);
    }

    public static ModelConfig defaults() {
        return new ModelConfig(null, null, null, null, null, null);
    }
}
