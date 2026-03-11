package com.platform.workflow.model;

import java.util.Map;

public record WorkflowTrigger(
        String type,
        Map<String, Object> config
) {}
