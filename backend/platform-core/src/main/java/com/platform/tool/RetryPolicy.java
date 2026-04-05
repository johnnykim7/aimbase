package com.platform.tool;

/**
 * CR-029: 도구 재시도 정책.
 */
public record RetryPolicy(
        int maxRetries,
        long backoffMs
) {
    public static final RetryPolicy NONE = new RetryPolicy(0, 0);
}
