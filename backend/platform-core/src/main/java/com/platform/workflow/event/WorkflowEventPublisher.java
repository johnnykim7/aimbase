package com.platform.workflow.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-058: 워크플로우 실행 이벤트 브로드캐스터.
 *
 * <p>{@link WorkflowEngine} 이 상태 전이 지점에서 publish 를 호출하고,
 * {@code WorkflowRunController#subscribe} SSE 엔드포인트가 {@code @EventListener} 로
 * 수신해 브라우저에 전달한다.
 *
 * <p>publish 는 fire-and-forget — 구독자가 없으면 유실되지만, 최초 연결 시점에
 * {@code workflow.snapshot} 이벤트로 DB 상태를 한 번 되감아 주므로 현재 상태는 정확히 복원된다.
 */
@Component
public class WorkflowEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventPublisher.class);

    private final ApplicationEventPublisher publisher;

    public WorkflowEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void stepRunning(UUID runId, UUID parentRunId, String stepId, Instant startedAt) {
        stepRunning(runId, parentRunId, stepId, startedAt, null);
    }

    /** CR-084 P4: cyclic 회차 구분 포함. iterationIndex=null 이면 기존(DAG) 동작과 동일. */
    public void stepRunning(UUID runId, UUID parentRunId, String stepId,
                            Instant startedAt, Integer iterationIndex) {
        safePublish(new WorkflowEvents.StepStatusChanged(
                runId, parentRunId, stepId, "running",
                startedAt, null, null, null, null, null, iterationIndex));
    }

    public void stepCompleted(UUID runId, UUID parentRunId, String stepId,
                              Instant startedAt, Instant completedAt,
                              String subWorkflowId, Map<String, Object> outputPreview) {
        stepCompleted(runId, parentRunId, stepId, startedAt, completedAt,
                subWorkflowId, outputPreview, null);
    }

    /** CR-084 P4: cyclic 회차 구분 포함. iterationIndex=null 이면 기존(DAG) 동작과 동일. */
    public void stepCompleted(UUID runId, UUID parentRunId, String stepId,
                              Instant startedAt, Instant completedAt,
                              String subWorkflowId, Map<String, Object> outputPreview,
                              Integer iterationIndex) {
        long duration = (startedAt != null && completedAt != null)
                ? (completedAt.toEpochMilli() - startedAt.toEpochMilli()) : 0L;
        safePublish(new WorkflowEvents.StepStatusChanged(
                runId, parentRunId, stepId, "completed",
                startedAt, completedAt, duration, subWorkflowId, outputPreview, null, iterationIndex));
    }

    public void stepFailed(UUID runId, UUID parentRunId, String stepId,
                           Instant startedAt, Instant failedAt, String errorMessage) {
        long duration = (startedAt != null && failedAt != null)
                ? (failedAt.toEpochMilli() - startedAt.toEpochMilli()) : 0L;
        safePublish(new WorkflowEvents.StepStatusChanged(
                runId, parentRunId, stepId, "failed",
                startedAt, failedAt, duration, null, null, errorMessage, null));
    }

    public void approvalRequired(UUID runId, UUID parentRunId, String stepId, String policyId,
                                 String reason, List<String> approvers, Instant timeoutAt) {
        safePublish(new WorkflowEvents.ApprovalRequired(
                runId, parentRunId, stepId, policyId, reason,
                approvers != null ? approvers : List.of(), timeoutAt));
    }

    public void runCompleted(UUID runId, UUID parentRunId, String status, long durationMs) {
        safePublish(new WorkflowEvents.RunCompleted(runId, parentRunId, status, durationMs));
    }

    private void safePublish(Object event) {
        try {
            publisher.publishEvent(event);
        } catch (Exception e) {
            // 이벤트 발행 실패가 워크플로우 실행을 막아서는 안 됨 (fire-and-forget).
            log.warn("Failed to publish workflow event: {} — {}", event.getClass().getSimpleName(), e.getMessage());
        }
    }
}
