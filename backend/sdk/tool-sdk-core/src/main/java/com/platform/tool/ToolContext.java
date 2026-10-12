package com.platform.tool;

import java.util.List;

/**
 * CR-029: 도구 실행 시 전달되는 문맥 정보.
 * 테넌트/앱/프로젝트/세션/워크플로우 식별, 권한, 워크스페이스 경로 등을 포함.
 *
 * <p>CR-102: subagentRunId 추가 — AGENT_CALL 스텝의 서브에이전트 도구 루프 이벤트를
 * workflow_run_events.subagent_run_id 로 연결 (멀티에이전트 병렬 시 이벤트 구분).
 *
 * <p>CR-147: skillSet 추가. null 이거나 비어 있으면 스킬 지정이 없는 호출이고 스킬 도구는 활성 묶음을 이름으로 해석한다.
 * 값이 있으면 스킬 도구는 그 이름과 판만 받는다(BIZ-121).
 */
public record ToolContext(
        String tenantId,
        String appId,
        String projectId,
        String sessionId,
        String workflowRunId,
        String stepId,
        String subagentRunId,
        String connectionId,
        String actorUserId,
        PermissionLevel permissionLevel,
        ApprovalState approvalState,
        String workspacePath,
        boolean dryRun,
        int turnNumber,
        List<SkillRef> skillSet
) {
    /** 14-arg 호환 (skillSet 없음, CR-147 이전 호출부용) */
    public ToolContext(String tenantId, String appId, String projectId, String sessionId,
                       String workflowRunId, String stepId, String subagentRunId, String connectionId,
                       String actorUserId, PermissionLevel permissionLevel, ApprovalState approvalState,
                       String workspacePath, boolean dryRun, int turnNumber) {
        this(tenantId, appId, projectId, sessionId, workflowRunId, stepId, subagentRunId, connectionId,
                actorUserId, permissionLevel, approvalState, workspacePath, dryRun, turnNumber, null);
    }

    /** 기존 12-arg 호환 (subagentRunId/connectionId 없음, CR-102 이전 호출부용) */
    public ToolContext(String tenantId, String appId, String projectId, String sessionId,
                       String workflowRunId, String stepId, String actorUserId,
                       PermissionLevel permissionLevel, ApprovalState approvalState,
                       String workspacePath, boolean dryRun, int turnNumber) {
        this(tenantId, appId, projectId, sessionId, workflowRunId, stepId, null, null,
                actorUserId, permissionLevel, approvalState, workspacePath, dryRun, turnNumber);
    }

    /** 13-arg 호환 (subagentRunId 있음, connectionId 없음 — CR-102 1·2차 호출부용) */
    public ToolContext(String tenantId, String appId, String projectId, String sessionId,
                       String workflowRunId, String stepId, String subagentRunId,
                       String actorUserId, PermissionLevel permissionLevel, ApprovalState approvalState,
                       String workspacePath, boolean dryRun, int turnNumber) {
        this(tenantId, appId, projectId, sessionId, workflowRunId, stepId, subagentRunId, null,
                actorUserId, permissionLevel, approvalState, workspacePath, dryRun, turnNumber);
    }

    /** 권한만 바꾼 사본. 나머지 칸(연결, 스킬 지정 포함)을 잃지 않게 이것으로 바꾼다. */
    public ToolContext withPermissionLevel(PermissionLevel level) {
        return new ToolContext(tenantId, appId, projectId, sessionId, workflowRunId, stepId, subagentRunId,
                connectionId, actorUserId, level, approvalState, workspacePath, dryRun, turnNumber, skillSet);
    }

    /** CR-147: 스킬 지정만 바꾼 사본. */
    public ToolContext withSkillSet(List<SkillRef> skills) {
        return new ToolContext(tenantId, appId, projectId, sessionId, workflowRunId, stepId, subagentRunId,
                connectionId, actorUserId, permissionLevel, approvalState, workspacePath, dryRun, turnNumber, skills);
    }

    /**
     * 현재 스레드의 TenantContext 등에서 최소 컨텍스트 생성.
     */
    public static ToolContext minimal(String tenantId, String sessionId) {
        return new ToolContext(
                tenantId, null, null, sessionId, null, null, null, null,
                PermissionLevel.READ_ONLY, ApprovalState.NOT_REQUIRED,
                null, false, 0
        );
    }
}
