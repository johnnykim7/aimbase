package com.platform.workflow.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-058: 워크플로우 실행 중 SSE 구독자에게 전달할 이벤트 3종.
 *
 * <p>{@link java.util.EventObject} 상속 없이 record 로 단순화. Spring 의
 * {@link org.springframework.context.ApplicationEventPublisher} 는 임의 객체를 이벤트로 받는다.
 */
public final class WorkflowEvents {

    private WorkflowEvents() {}

    /** 스텝 상태 전이(RUNNING / COMPLETED / FAILED). */
    public record StepStatusChanged(
            UUID runId,
            UUID parentRunId,
            String stepId,
            String status,                     // "running" | "completed" | "failed"
            Instant startedAt,
            Instant completedAt,
            Long durationMs,
            String subWorkflowId,              // SUB_WORKFLOW 스텝일 때 자식 runId (null 허용)
            Map<String, Object> outputPreview, // completed 시 소량 미리보기 (null 허용)
            String errorMessage,               // failed 시에만
            // CR-084 P4: cyclic 그래프에서 같은 stepId 가 N회 실행될 때 회차 구분.
            // DAG 모드/기존 호출은 null (하위호환 — 0-based, null=비순환).
            Integer iterationIndex
    ) {}

    /** HUMAN_INPUT (REQUIRE_APPROVAL) 대기 알림. */
    public record ApprovalRequired(
            UUID runId,
            UUID parentRunId,
            String stepId,
            String policyId,
            String reason,
            List<String> approvers,
            Instant timeoutAt
    ) {}

    /** 전체 런 종료(completed / failed / cancelled). */
    public record RunCompleted(
            UUID runId,
            UUID parentRunId,
            String status,
            long durationMs
    ) {}
}
