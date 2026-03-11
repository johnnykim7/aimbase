package com.platform.workflow.model;

public record ErrorHandling(
        String defaultStep,
        int retryMaxAttempts,
        long retryDelayMs,
        long timeoutMs
) {
    public static ErrorHandling defaults() {
        return new ErrorHandling(null, 2, 3000L, 120000L);
    }
}
