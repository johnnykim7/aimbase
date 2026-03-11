package com.platform.tenant.quota;

/**
 * 테넌트 쿼터 초과 예외 (HTTP 429 Too Many Requests).
 */
public class QuotaExceededException extends RuntimeException {

    private final long quota;
    private final long currentUsage;
    private final long requested;

    public QuotaExceededException(String message, long quota, long currentUsage, long requested) {
        super(message);
        this.quota = quota;
        this.currentUsage = currentUsage;
        this.requested = requested;
    }

    public long getQuota() { return quota; }
    public long getCurrentUsage() { return currentUsage; }
    public long getRequested() { return requested; }
}
