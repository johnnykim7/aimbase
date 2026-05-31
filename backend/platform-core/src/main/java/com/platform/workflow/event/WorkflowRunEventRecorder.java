package com.platform.workflow.event;

import com.platform.domain.WorkflowRunEventEntity;
import com.platform.domain.WorkflowRunEventEntity.EventType;
import com.platform.repository.WorkflowRunEventRepository;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CR-090: 워크플로우 실행 이벤트 비동기 기록.
 *
 * <p>모든 publish 메서드는 fire-and-forget — Virtual Thread 에서 INSERT 실행하고
 * 예외는 삼킴 (log.warn). 워크플로우 hot path 에 지연 0.
 *
 * <p>TenantContext 는 호출 시점에 캡처 후 새 스레드에 재주입 (DataSource 라우팅 보장).
 */
@Component
public class WorkflowRunEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunEventRecorder.class);
    private static final int PREVIEW_MAX = 100;

    private final WorkflowRunEventRepository repository;

    public WorkflowRunEventRecorder(WorkflowRunEventRepository repository) {
        this.repository = repository;
    }

    public void stepStart(UUID runId, String stepId, String stepType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (stepType != null) payload.put("step_type", stepType);
        publish(buildEvent(runId, stepId, null, EventType.STEP_START, null, null, payload, null, null));
    }

    public void stepEnd(UUID runId, String stepId, long durationMs, int outputSize) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("output_size", outputSize);
        publish(buildEvent(runId, stepId, null, EventType.STEP_END, null, durationMs, payload, null, null));
    }

    public void stepFailed(UUID runId, String stepId, long durationMs, String error, int attempts) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (error != null) payload.put("error", truncate(error, 500));
        payload.put("attempts", attempts);
        publish(buildEvent(runId, stepId, null, EventType.STEP_FAILED, null, durationMs, payload, null, null));
    }

    public void toolUse(UUID runId, String stepId, Integer iteration, String toolName,
                        Map<String, Object> input, UUID subagentRunId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (input != null && !input.isEmpty()) {
            payload.put("input_keys", input.keySet());
            payload.put("input_preview", truncate(input.toString(), PREVIEW_MAX));
        }
        publish(buildEvent(runId, stepId, iteration, EventType.TOOL_USE, toolName, null, payload, null, subagentRunId));
    }

    public void toolResult(UUID runId, String stepId, Integer iteration, String toolName,
                           long durationMs, boolean ok, String error, int outputSize, UUID subagentRunId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("output_size", outputSize);
        payload.put("ok", ok);
        if (error != null) payload.put("error", truncate(error, 200));
        publish(buildEvent(runId, stepId, iteration, EventType.TOOL_RESULT, toolName, durationMs, payload, null, subagentRunId));
    }

    public void llmResponse(UUID runId, String stepId, Integer iteration,
                            String model, int inputTokens, int outputTokens,
                            String finishReason, long durationMs,
                            String traceId, UUID subagentRunId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (model != null) payload.put("model", model);
        payload.put("in_tok", inputTokens);
        payload.put("out_tok", outputTokens);
        if (finishReason != null) payload.put("finish_reason", finishReason);
        publish(buildEvent(runId, stepId, iteration, EventType.LLM_RESPONSE, null, durationMs, payload, traceId, subagentRunId));
    }

    // ── 내부 ──

    private WorkflowRunEventEntity buildEvent(UUID runId, String stepId, Integer iteration,
                                              EventType type, String toolName, Long durationMs,
                                              Map<String, Object> payload,
                                              String traceId, UUID subagentRunId) {
        WorkflowRunEventEntity e = new WorkflowRunEventEntity();
        e.setRunId(runId);
        e.setStepId(stepId);
        e.setIteration(iteration);
        e.setEventType(type);
        e.setToolName(toolName);
        e.setDurationMs(durationMs);
        e.setPayload(payload != null && !payload.isEmpty() ? payload : null);
        e.setTraceId(traceId);
        e.setSubagentRunId(subagentRunId);
        return e;
    }

    private void publish(WorkflowRunEventEntity event) {
        if (event.getRunId() == null) return; // 안전망 — runId 없으면 의미 없음
        String tenantId = TenantContext.getTenantId();
        Thread.ofVirtual().name("wfre-record-" + UUID.randomUUID().toString().substring(0, 8)).start(() -> {
            try {
                if (tenantId != null) TenantContext.setTenantId(tenantId);
                repository.save(event);
            } catch (Exception ex) {
                log.warn("Failed to record workflow run event (run={}, type={}): {}",
                        event.getRunId(), event.getEventType(), ex.getMessage());
            } finally {
                TenantContext.clear();
            }
        });
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
