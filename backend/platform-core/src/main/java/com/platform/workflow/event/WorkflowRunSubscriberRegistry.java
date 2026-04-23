package com.platform.workflow.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * CR-058: 워크플로우 런 구독자 레지스트리 + 이벤트 브릿지.
 *
 * <p>{@code GET /workflows/runs/{runId}/subscribe} 가 emitter 를 등록하면
 * {@link WorkflowEventPublisher} 가 발행한 이벤트를 필터링해 해당 emitter 에 forward 한다.
 * 필터 규칙: {@code event.runId == subscribed runId} 또는
 * {@code event.parentRunId == subscribed runId} (서브워크플로우 자식 이벤트도 부모 구독자에게 포함).
 */
@Component
public class WorkflowRunSubscriberRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunSubscriberRegistry.class);

    private final ConcurrentMap<UUID, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /** SSE emitter 를 특정 runId 구독자 목록에 등록. */
    public void register(UUID runId, SseEmitter emitter) {
        subscribers.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(t -> remove(runId, emitter));
    }

    private void remove(UUID runId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(runId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) subscribers.remove(runId);
        }
    }

    @EventListener
    public void onStepStatus(WorkflowEvents.StepStatusChanged ev) {
        broadcast(ev.runId(), ev.parentRunId(), "workflow.step", toStepPayload(ev));
    }

    @EventListener
    public void onApproval(WorkflowEvents.ApprovalRequired ev) {
        broadcast(ev.runId(), ev.parentRunId(), "workflow.approval", toApprovalPayload(ev));
    }

    @EventListener
    public void onRunCompleted(WorkflowEvents.RunCompleted ev) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("run_id", ev.runId().toString());
        if (ev.parentRunId() != null) payload.put("parent_run_id", ev.parentRunId().toString());
        payload.put("status", ev.status());
        payload.put("duration_ms", ev.durationMs());
        broadcast(ev.runId(), ev.parentRunId(), "workflow.done", payload);
    }

    private void broadcast(UUID runId, UUID parentRunId, String name, Map<String, Object> payload) {
        fanOut(runId, name, payload);
        if (parentRunId != null && !parentRunId.equals(runId)) {
            fanOut(parentRunId, name, payload);
        }
    }

    private void fanOut(UUID runId, String name, Map<String, Object> payload) {
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(runId);
        if (list == null || list.isEmpty()) return;
        for (SseEmitter em : list) {
            try {
                em.send(SseEmitter.event().name(name).data(payload));
            } catch (IOException | IllegalStateException e) {
                log.debug("SSE send failed, removing emitter: {}", e.getMessage());
                remove(runId, em);
            }
        }
    }

    private Map<String, Object> toStepPayload(WorkflowEvents.StepStatusChanged ev) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("run_id", ev.runId().toString());
        if (ev.parentRunId() != null) m.put("parent_run_id", ev.parentRunId().toString());
        m.put("step_id", ev.stepId());
        m.put("status", ev.status());
        if (ev.startedAt() != null) m.put("started_at", ev.startedAt().toString());
        if (ev.completedAt() != null) m.put("completed_at", ev.completedAt().toString());
        if (ev.durationMs() != null) m.put("duration_ms", ev.durationMs());
        if (ev.subWorkflowId() != null) m.put("sub_workflow_id", ev.subWorkflowId());
        if (ev.outputPreview() != null) m.put("output_preview", ev.outputPreview());
        if (ev.errorMessage() != null) m.put("error", ev.errorMessage());
        return m;
    }

    private Map<String, Object> toApprovalPayload(WorkflowEvents.ApprovalRequired ev) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("run_id", ev.runId().toString());
        if (ev.parentRunId() != null) m.put("parent_run_id", ev.parentRunId().toString());
        m.put("step_id", ev.stepId());
        m.put("policy_id", ev.policyId());
        if (ev.reason() != null) m.put("reason", ev.reason());
        m.put("approvers", ev.approvers());
        if (ev.timeoutAt() != null) m.put("timeout_at", ev.timeoutAt().toString());
        return m;
    }

    /** 테스트용 — 현재 runId 구독자 수. */
    public int subscriberCount(UUID runId) {
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(runId);
        return list == null ? 0 : list.size();
    }
}
