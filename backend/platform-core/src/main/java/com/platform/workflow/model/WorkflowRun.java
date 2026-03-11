package com.platform.workflow.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public record WorkflowRun(
        String id,
        String workflowId,
        String sessionId,
        RunStatus status,
        String currentStep,
        Map<String, Object> stepResults,
        Instant startedAt,
        Instant completedAt
) {
    public enum RunStatus {
        RUNNING, COMPLETED, FAILED, PENDING_APPROVAL, CANCELLED
    }

    public static WorkflowRun start(String workflowId, String sessionId) {
        return new WorkflowRun(
                java.util.UUID.randomUUID().toString(),
                workflowId,
                sessionId,
                RunStatus.RUNNING,
                null,
                new HashMap<>(),
                Instant.now(),
                null
        );
    }
}
