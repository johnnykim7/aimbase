package com.platform.tool;

import java.util.List;
import java.util.Map;

/**
 * CR-029: 도구 실행 결과.
 * LLM에는 summary만 전달하고, 전체 output/artifacts는 lineage에 보관.
 */
public record ToolResult(
        boolean success,
        Object output,
        String summary,
        List<ToolArtifact> artifacts,
        List<String> sideEffects,
        Map<String, Object> auditPayload,
        String nextContextHint,
        long durationMs
) {
    /** 간단한 성공 결과 생성 */
    public static ToolResult ok(Object output, String summary) {
        return new ToolResult(true, output, summary, List.of(), List.of(), Map.of(), null, 0);
    }

    /** 간단한 에러 결과 생성 */
    public static ToolResult error(String message) {
        return new ToolResult(false, null, message, List.of(), List.of(), Map.of(), null, 0);
    }

    /**
     * C1/C3: Permission 또는 Workspace Policy 거부.
     * summary에 "[DENIED]" 프리픽스로 denial 여부를 표시.
     */
    public static ToolResult denied(String reason) {
        return new ToolResult(false, null, "[DENIED] " + reason, List.of(), List.of(), Map.of(), null, 0);
    }

    /** C3: 이 결과가 실행 거부(denied)인지 판별 */
    public boolean isDenied() {
        return !success && summary != null && summary.startsWith("[DENIED]");
    }

    /** durationMs를 설정한 복사본 반환 */
    public ToolResult withDuration(long durationMs) {
        return new ToolResult(success, output, summary, artifacts, sideEffects, auditPayload, nextContextHint, durationMs);
    }
}
