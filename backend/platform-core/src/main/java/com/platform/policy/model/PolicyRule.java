package com.platform.policy.model;

import java.util.Map;

public record PolicyRule(
        PolicyRuleType type,
        String condition,
        String message,
        Map<String, Object> config
) {
    public enum PolicyRuleType {
        ALLOW, DENY, REQUIRE_APPROVAL, TRANSFORM, RATE_LIMIT, LOG
    }
}
