package com.platform.policy.model;

import java.util.List;
import java.util.Map;

public record PolicyResult(
        boolean passed,
        PolicyAction action,
        List<TriggeredPolicy> triggeredPolicies,
        Map<String, Object> transforms,
        String denialReason
) {
    public enum PolicyAction {
        ALLOW, DENY, REQUIRE_APPROVAL
    }

    public static PolicyResult allow() {
        return new PolicyResult(true, PolicyAction.ALLOW, List.of(), null, null);
    }

    public static PolicyResult deny(String reason) {
        return new PolicyResult(false, PolicyAction.DENY, List.of(), null, reason);
    }

    public static PolicyResult requireApproval() {
        return new PolicyResult(true, PolicyAction.REQUIRE_APPROVAL, List.of(), null, null);
    }
}
