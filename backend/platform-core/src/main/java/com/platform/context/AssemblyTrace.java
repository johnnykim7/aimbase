package com.platform.context;

import java.util.List;

/**
 * CR-029: 컨텍스트 조립 과정 추적 — 비교 가능성을 위한 구조화된 메타데이터.
 */
public record AssemblyTrace(
        String recipeId,
        String resolveReason,
        List<AssembledLayer> includedLayers,
        List<String> evictedLayers,
        List<String> staleLayers,
        int totalEstimatedTokens,
        int effectiveWindow,
        long assemblyDurationMs
) {}
