package com.platform.action.model;

public record ActionPolicy(
        boolean requiresApproval,
        String approvalChannel,
        int retryMaxAttempts,
        long retryDelayMs,
        boolean auditLog
) {
    public static ActionPolicy defaults() {
        return new ActionPolicy(false, null, 3, 3000L, true);
    }
}
