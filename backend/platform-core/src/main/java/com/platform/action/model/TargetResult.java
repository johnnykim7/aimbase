package com.platform.action.model;

import java.util.Map;

public record TargetResult(
        ActionTarget target,
        String status,
        Map<String, Object> data,
        String error
) {
    public static TargetResult success(ActionTarget target, Map<String, Object> data) {
        return new TargetResult(target, "success", data, null);
    }

    public static TargetResult failure(ActionTarget target, String error) {
        return new TargetResult(target, "failed", null, error);
    }
}
