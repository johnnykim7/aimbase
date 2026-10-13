package com.platform.workflow.model;

import java.util.Map;
import java.util.Set;

/**
 * 워크플로우 스텝 재시도 설정(workflows.error_handling).
 *
 * <p>CR-149: 저장 API 는 KEYS 밖의 칸을 거부한다. 그 전에 저장된 행에는 가이드의 옛 이름
 * (strategy, maxRetries)이 남아 있을 수 있어, 실행 때는 모르는 칸을 무시하고 없는 칸만 기본값으로 채운다.
 * 예전에는 모르는 칸이 하나라도 있으면 통째로 기본값이 됐고, 칸 하나만 적으면 나머지가 0 이 됐다.
 */
public record ErrorHandling(int retryMaxAttempts, long retryDelayMs) {

    public static final String RETRY_MAX_ATTEMPTS = "retryMaxAttempts";
    public static final String RETRY_DELAY_MS = "retryDelayMs";
    public static final Set<String> KEYS = Set.of(RETRY_MAX_ATTEMPTS, RETRY_DELAY_MS);

    private static final int DEFAULT_RETRY_MAX_ATTEMPTS = 2;
    private static final long DEFAULT_RETRY_DELAY_MS = 3000L;

    public static ErrorHandling defaults() {
        return new ErrorHandling(DEFAULT_RETRY_MAX_ATTEMPTS, DEFAULT_RETRY_DELAY_MS);
    }

    public static ErrorHandling from(Map<String, Object> raw) {
        if (raw == null) return defaults();
        return new ErrorHandling(
                (int) Math.min(Integer.MAX_VALUE, nonNegative(raw.get(RETRY_MAX_ATTEMPTS), DEFAULT_RETRY_MAX_ATTEMPTS)),
                nonNegative(raw.get(RETRY_DELAY_MS), DEFAULT_RETRY_DELAY_MS));
    }

    private static long nonNegative(Object value, long fallback) {
        return value instanceof Number n && n.longValue() >= 0 ? n.longValue() : fallback;
    }
}
