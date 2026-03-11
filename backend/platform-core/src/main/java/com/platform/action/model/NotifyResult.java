package com.platform.action.model;

public record NotifyResult(
        boolean success,
        boolean delivered,
        String messageId,
        String error
) {
    public static NotifyResult success(String messageId) {
        return new NotifyResult(true, true, messageId, null);
    }

    public static NotifyResult failure(String error) {
        return new NotifyResult(false, false, null, error);
    }
}
