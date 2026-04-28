package com.platform.tool;

import java.util.List;

/**
 * CR-029: 도구 계약 메타데이터.
 * 도구의 식별, 권한 수준, 부작용 여부, 동시성 안전성 등을 선언.
 *
 * <p>CR-072: {@code mcpExposureLevel} 추가 — 서버 측 ToolRegistry 도구를
 * MCP endpoint(/mcp/sse) 로 외부(CLI 등) 노출할지 여부. 기본값 {@link McpExposureLevel#NONE}.</p>
 */
public record ToolContractMeta(
        String id,
        String version,
        ToolScope scope,
        PermissionLevel permissionLevel,
        boolean approvalRequired,
        boolean readOnly,
        boolean destructive,
        boolean concurrencySafe,
        RetryPolicy retryPolicy,
        List<String> tags,
        List<String> capabilities,
        McpExposureLevel mcpExposureLevel
) {
    /** 기존 호출 사이트 호환용: mcpExposureLevel 기본값 NONE. */
    public ToolContractMeta(
            String id,
            String version,
            ToolScope scope,
            PermissionLevel permissionLevel,
            boolean approvalRequired,
            boolean readOnly,
            boolean destructive,
            boolean concurrencySafe,
            RetryPolicy retryPolicy,
            List<String> tags,
            List<String> capabilities
    ) {
        this(id, version, scope, permissionLevel, approvalRequired, readOnly,
                destructive, concurrencySafe, retryPolicy, tags, capabilities,
                McpExposureLevel.NONE);
    }

    /** 읽기 전용 네이티브 도구의 기본 계약 생성 */
    public static ToolContractMeta readOnlyNative(String id, List<String> tags) {
        return new ToolContractMeta(
                id, "1.0", ToolScope.NATIVE, PermissionLevel.READ_ONLY,
                false, true, false, true,
                RetryPolicy.NONE, tags, List.of("read", "search"),
                McpExposureLevel.NONE
        );
    }

    /** CR-072: 기존 메타에 MCP 노출 레벨만 덮어쓴 새 인스턴스 */
    public ToolContractMeta withMcpExposure(McpExposureLevel level) {
        return new ToolContractMeta(
                id, version, scope, permissionLevel, approvalRequired, readOnly,
                destructive, concurrencySafe, retryPolicy, tags, capabilities, level
        );
    }
}
