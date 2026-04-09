package com.platform.llm.model;

import java.util.List;

public record ModelConfig(
        Double temperature,
        Integer maxTokens,
        Double topP,
        List<String> stopSequences,
        Boolean extendedThinking,        // CR-030: Extended Thinking 활성화
        Integer thinkingBudgetTokens,    // CR-030: thinking 토큰 예산 (≥1024, < maxTokens)
        ThinkingMode thinkingMode        // CR-031: DISABLED/ENABLED/ADAPTIVE 3모드
) {
    /** 하위 호환: 기존 4-파라미터 생성자 */
    public ModelConfig(Double temperature, Integer maxTokens, Double topP, List<String> stopSequences) {
        this(temperature, maxTokens, topP, stopSequences, null, null, null);
    }

    /** 하위 호환: 기존 6-파라미터 생성자 (CR-030) */
    public ModelConfig(Double temperature, Integer maxTokens, Double topP,
                       List<String> stopSequences, Boolean extendedThinking, Integer thinkingBudgetTokens) {
        this(temperature, maxTokens, topP, stopSequences, extendedThinking, thinkingBudgetTokens, null);
    }

    /**
     * CR-031: 유효한 ThinkingMode를 반환한다.
     * thinkingMode가 명시되면 그것을 우선 사용.
     * 미지정 시 extendedThinking boolean으로 폴백 (하위 호환).
     */
    public ThinkingMode resolveThinkingMode() {
        if (thinkingMode != null) return thinkingMode;
        if (Boolean.TRUE.equals(extendedThinking)) return ThinkingMode.ENABLED;
        return ThinkingMode.DISABLED;
    }

    public static ModelConfig defaults() {
        return new ModelConfig(null, null, null, null, null, null, null);
    }
}
