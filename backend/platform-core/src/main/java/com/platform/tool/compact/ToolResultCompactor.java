package com.platform.tool.compact;

/**
 * CR-031 PRD-215: 도구 결과 지능형 축약 인터페이스.
 *
 * 도구별로 결과를 의미 있게 축약하여 컨텍스트 낭비를 최소화한다.
 */
public interface ToolResultCompactor {

    /**
     * 이 compactor가 해당 도구를 처리할 수 있는지 판별.
     */
    boolean supports(String toolName);

    /**
     * 결과를 축약한다.
     *
     * @param toolName   도구 이름
     * @param rawOutput  원본 결과 문자열
     * @param maxChars   축약 목표 최대 문자 수
     * @return 축약된 결과
     */
    String compact(String toolName, String rawOutput, int maxChars);
}
