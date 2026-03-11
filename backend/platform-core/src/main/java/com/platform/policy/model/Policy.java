package com.platform.policy.model;

import java.util.List;

public record Policy(
        String id,
        String name,
        String domain,
        int priority,
        boolean isActive,
        PolicyMatch match,
        List<PolicyRule> rules
) {}
