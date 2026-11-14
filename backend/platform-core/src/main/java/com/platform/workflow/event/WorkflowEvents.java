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

    /**
     * CR-085 P3: 노드 내부 LLM 토큰 델타 스트리밍.
     *
     * <p>opt-in (스텝 config {@code stream_tokens:true}) 일 때만 발행. 미지정 시 기존
     * step 단위 {@link StepStatusChanged} 이벤트만 — 하위호환. cyclic 그래프에서 같은
     * 노드가 N회 실행되므로 {@code iterationIndex} 로 회차 구분 (DAG/null=비순환,
     * {@link StepStatusChanged#iterationIndex} 와 동일 계약).
     */
    public record StepToken(
            UUID runId,
            UUID parentRunId,
            String stepId,
            Integer iterationIndex,   // DAG/기존 = null
            String tokenDelta,        // 누적 텍스트가 아닌 증분 델타
            String type               // "text" | "thinking" (LLMStreamChunk.type 그대로)
    ) {}

    /** HUMAN_INPUT (REQUIRE_APPROVAL) 대기 알림. */
    public record ApprovalRequired(
            UUID runId,
            UUID parentRunId,
            String stepId,
            String policyId,
            String reason,
            List<String> approvers,
            Instant timeoutAt,
            UUID approvalId           // pending_approvals.id. 구독 때 다시 보낸 것과 실시간 알림이 겹치면 위젯이 이것으로 하나만 그린다
    ) {}

    /**
     * 전체 런 종료(completed / failed / cancelled).
     * error 는 실패한 런에 저장한 error 와 같은 값이다(CR-177, 위젯 승인 카드가 실패 이유를 보인다). 없으면 null.
     */
    public record RunCompleted(
            UUID runId,
            UUID parentRunId,
            String status,
            long durationMs,
            Map<String, Object> error
    ) {
        public RunCompleted(UUID runId, UUID parentRunId, String status, long durationMs) {
            this(runId, parentRunId, status, durationMs, null);
        }
    }
}
