package com.platform.runtime;

import java.util.List;

/**
 * CR-029 (PRD-183): 런타임 능력 프로필.
 */
public record RuntimeCapabilityProfile(
        boolean supportsStreaming,
        boolean supportsToolUse,
        boolean supportsMultiTurn,
        boolean supportsLongContext,
        boolean supportsAutonomousExploration,
        boolean supportsStructuredOutput,
        int maxContextTokens,
        List<String> supportedModels,
        List<String> strengths
) {}
