package com.platform.workflow.model;

import java.util.List;
import java.util.Map;

public record WorkflowStep(
        String id,
        StepType type,
        Map<String, Object> config,
        List<String> dependsOn,
        String onSuccess,
        String onFailure,
        Long timeoutMs
) {
    public enum StepType {
        LLM_CALL, TOOL_CALL, ACTION, CONDITION, PARALLEL, HUMAN_INPUT
    }
}
