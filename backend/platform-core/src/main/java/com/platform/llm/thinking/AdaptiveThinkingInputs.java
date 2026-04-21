package com.platform.llm.thinking;

/**
 * CR-048 PRD-302: Adaptive thinking budget 계산 입력 지표.
 *
 * @param lastTurnToolCalls 직전 턴에서 tool_use 호출 수 (복잡도 지표)
 * @param lastTurnHadErrors 직전 턴에서 도구 에러/재시도 발생 여부
 * @param userQuestionLength 현재 사용자 질문 문자 수
 */
public record AdaptiveThinkingInputs(
        int lastTurnToolCalls,
        boolean lastTurnHadErrors,
        int userQuestionLength
) {
    public static AdaptiveThinkingInputs empty() {
        return new AdaptiveThinkingInputs(0, false, 0);
    }
}
