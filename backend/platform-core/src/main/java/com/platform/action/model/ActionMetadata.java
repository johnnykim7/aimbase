package com.platform.action.model;

public record ActionMetadata(
        String sessionId,
        String userId,
        String workflowRunId
) {}
