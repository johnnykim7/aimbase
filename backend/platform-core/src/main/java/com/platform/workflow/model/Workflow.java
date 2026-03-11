package com.platform.workflow.model;

import java.util.List;

public record Workflow(
        String id,
        String name,
        String domain,
        WorkflowTrigger trigger,
        List<WorkflowStep> steps,
        ErrorHandling errorHandling
) {}
