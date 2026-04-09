package com.platform.tool;

import java.util.List;

/**
 * CR-029: 도구 계약 메타데이터.
 * 도구의 식별, 권한 수준, 부작용 여부, 동시성 안전성 등을 선언.
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
        List<String> capabilities
) {
    /** 읽기 전용 네이티브 도구의 기본 계약 생성 */
    public static ToolContractMeta readOnlyNative(String id, List<String> tags) {
        return new ToolContractMeta(
                id, "1.0", ToolScope.NATIVE, PermissionLevel.READ_ONLY,
                false, true, false, true,
                RetryPolicy.NONE, tags, List.of("read", "search")
        );
    }
}
