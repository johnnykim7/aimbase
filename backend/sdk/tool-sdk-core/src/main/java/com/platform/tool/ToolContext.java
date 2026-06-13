package com.platform.tool;

/**
 * CR-029: 도구 실행 시 전달되는 문맥 정보.
 * 테넌트/앱/프로젝트/세션/워크플로우 식별, 권한, 워크스페이스 경로 등을 포함.
 *
 * <p>CR-102: subagentRunId 추가 — AGENT_CALL 스텝의 서브에이전트 도구 루프 이벤트를
 * workflow_run_events.subagent_run_id 로 연결 (멀티에이전트 병렬 시 이벤트 구분).
 */
public record ToolContext(
        String tenantId,
        String appId,
        String projectId,
        String sessionId,
        String workflowRunId,
        String stepId,
        String subagentRunId,
        String actorUserId,
        PermissionLevel permissionLevel,
        ApprovalState approvalState,
        String workspacePath,
        boolean dryRun,
        int turnNumber
) {
    /** 기존 12-arg 호환 (subagentRunId 없음, CR-102 이전 호출부용) */
    public ToolContext(String tenantId, String appId, String projectId, String sessionId,
                       String workflowRunId, String stepId, String actorUserId,
                       PermissionLevel permissionLevel, ApprovalState approvalState,
                       String workspacePath, boolean dryRun, int turnNumber) {
        this(tenantId, appId, projectId, sessionId, workflowRunId, stepId, null,
                actorUserId, permissionLevel, approvalState, workspacePath, dryRun, turnNumber);
    }

    /**
     * 현재 스레드의 TenantContext 등에서 최소 컨텍스트 생성.
     */
    public static ToolContext minimal(String tenantId, String sessionId) {
        return new ToolContext(
                tenantId, null, null, sessionId, null, null, null,
                PermissionLevel.READ_ONLY, ApprovalState.NOT_REQUIRED,
                null, false, 0
        );
    }
}
