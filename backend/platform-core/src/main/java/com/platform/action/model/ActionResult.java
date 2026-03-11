package com.platform.action.model;

import com.platform.policy.model.PolicyResult;

import java.util.List;

public record ActionResult(
        String actionId,
        ActionStatus status,
        List<TargetResult> results,
        PolicyResult policyEvaluation
) {
    public enum ActionStatus {
        SUCCESS, FAILED, PENDING_APPROVAL, REJECTED
    }

    public boolean isSuccessful() {
        return status == ActionStatus.SUCCESS;
    }
}
