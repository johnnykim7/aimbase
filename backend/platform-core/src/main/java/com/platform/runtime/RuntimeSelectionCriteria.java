package com.platform.runtime;

/**
 * CR-029: 런타임 자동 선택 기준.
 */
public record RuntimeSelectionCriteria(
        String preferredRuntime,
        boolean requiresToolUse,
        boolean requiresLongContext,
        boolean requiresAutonomous,
        String taskType
) {}
