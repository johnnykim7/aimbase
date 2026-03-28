package com.platform.workflow.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowStep(
        String id,
        String name,
        StepType type,
        Map<String, Object> config,
        @JsonAlias("dependencies") List<String> dependsOn,
        String onSuccess,
        String onFailure,
        Long timeoutMs
) {
    public enum StepType {
        LLM_CALL, TOOL_CALL, ACTION, CONDITION, PARALLEL, HUMAN_INPUT
    }
}
