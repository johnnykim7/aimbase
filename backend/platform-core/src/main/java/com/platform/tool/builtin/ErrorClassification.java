package com.platform.tool.builtin;

/**
 * CLI 에러 분류 결과.
 * 에러 패턴 매칭 후 에러 유형과 대응 액션을 담는다.
 */
public record ErrorClassification(
        String errorType,   // AUTH_EXPIRED, RATE_LIMIT, NETWORK, TIMEOUT, MAX_TURNS, UNKNOWN
        String action,      // NOTIFY, RETRY, CIRCUIT_BREAKER
        String pattern      // 매칭된 패턴 문자열 (null이면 미매칭)
) {
    /** 패턴 미매칭 시 기본 분류 */
    public static ErrorClassification unknown() {
        return new ErrorClassification("UNKNOWN", "CIRCUIT_BREAKER", null);
    }
}
