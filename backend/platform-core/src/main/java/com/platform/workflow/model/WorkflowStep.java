package com.platform.workflow.model;

import java.util.List;
import java.util.Map;

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowStep(
        String id,
        String name,
        StepType type,
        Map<String, Object> config,
        List<String> dependsOn,
        String onSuccess,
        String onFailure,
        Long timeoutMs
) {
    public enum StepType {
        LLM_CALL, TOOL_CALL, ACTION, CONDITION, PARALLEL, HUMAN_INPUT, SUB_WORKFLOW, AGENT_CALL
    }
}
