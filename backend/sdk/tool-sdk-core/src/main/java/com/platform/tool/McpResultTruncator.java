package com.platform.tool;

/**
 * CR-044 PRD-281: MCP 응답 결과 축약 유틸리티 (tool-sdk-core 레이어).
 *
 * Claude CLI는 MCP 응답 전문을 모델 컨텍스트에 삽입한다.
 * 긴 grep/파일 읽기 결과가 그대로 들어가면 Max 플랜 rate limit을 빠르게 소진한다.
 * MCP 응답 직전에 이 클래스로 결과를 축약하여 컨텍스트 토큰 소비를 줄인다.
 *
 * 의존성 없음 (순수 Java) — tool-sdk-mcp에서 Spring 없이 사용 가능.
 */
public final class McpResultTruncator {

    /** 기본 최대 문자 수 (약 2K 토큰 수준) */
    public static final int DEFAULT_MAX_CHARS = 8_000;

    /** 앞쪽 보존 비율 (전체 허용량의 70%) */
    private static final double HEAD_RATIO = 0.70;

    private McpResultTruncator() {}

    /**
     * 결과 문자열을 maxChars 이내로 축약한다.
     * 앞 70% + 생략 표시 + 뒤 30% 구조로 문맥을 최대한 보존한다.
     *
     * @param toolName 도구 이름 (생략 메시지에 표시)
     * @param result   원본 결과 문자열
     * @param maxChars 최대 허용 문자 수
     * @return 축약된 결과 (maxChars 이하이면 원본 그대로 반환)
     */
    public static String truncate(String toolName, String result, int maxChars) {
        if (result == null || result.length() <= maxChars) {
            return result;
        }
        int headLen = (int) (maxChars * HEAD_RATIO);
        int tailLen = maxChars - headLen;
        String omittedNotice = "\n\n... [%s 결과 축약: 원본 %,d자 → %,d자 표시. 중간 %,d자 생략] ...\n\n"
                .formatted(toolName, result.length(), maxChars,
                        result.length() - headLen - tailLen);
        return result.substring(0, headLen)
                + omittedNotice
                + result.substring(result.length() - tailLen);
    }

    /**
     * DEFAULT_MAX_CHARS 기준으로 축약한다.
     */
    public static String truncate(String toolName, String result) {
        return truncate(toolName, result, DEFAULT_MAX_CHARS);
    }
}
