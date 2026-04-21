package com.platform.llm.thinking;

/**
 * CR-048 PRD-302: ADAPTIVE 모드에서 턴별 복잡도 지표를 바탕으로 thinking budget을 동적 조정.
 */
public interface AdaptiveThinkingPolicy {

    /**
     * 입력 지표로부터 thinking budget(토큰 수)을 계산.
     *
     * @param inputs 직전 턴 tool_use 수, 에러 발생, 현재 질문 길이
     * @return budget tokens (기본/증배 적용 후, cap 적용됨)
     */
    int calculate(AdaptiveThinkingInputs inputs);
}
