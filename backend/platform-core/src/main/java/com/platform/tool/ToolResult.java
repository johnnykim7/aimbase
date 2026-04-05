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

    /** durationMs를 설정한 복사본 반환 */
    public ToolResult withDuration(long durationMs) {
        return new ToolResult(success, output, summary, artifacts, sideEffects, auditPayload, nextContextHint, durationMs);
    }
}
