package com.platform.policy.model;

public record TriggeredPolicy(
        String policyId,
        int ruleIndex,
        String result
) {}
