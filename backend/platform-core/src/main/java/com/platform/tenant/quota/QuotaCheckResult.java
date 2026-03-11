package com.platform.tenant.quota;

/**
 * 쿼터 체크 결과.
 */
public record QuotaCheckResult(
    boolean allowed,
    long remaining,
    String reason,
    long quota,
    long currentUsage,
    long requested
) {
    public static QuotaCheckResult allowed(long remaining) {
        return new QuotaCheckResult(true, remaining, null, 0, 0, 0);
    }

    public static QuotaCheckResult exceeded(String reason, long quota, long currentUsage, long requested) {
        return new QuotaCheckResult(false, 0, reason, quota, currentUsage, requested);
    }

    public void throwIfExceeded() {
        if (!allowed) {
            throw new QuotaExceededException(reason, quota, currentUsage, requested);
        }
    }
}
