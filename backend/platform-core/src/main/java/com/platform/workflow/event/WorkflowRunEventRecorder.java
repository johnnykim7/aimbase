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
        stepEnd(runId, stepId, durationMs, outputSize, null);
    }

    /** CR-102: 단계 결과 본문(outputBody) 전문 적재 — 단계 간 데이터 전달 품질 검토용. */
    public void stepEnd(UUID runId, String stepId, long durationMs, int outputSize, String outputBody) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("output_size", outputSize);
        WorkflowRunEventEntity e = buildEvent(runId, stepId, null, EventType.STEP_END, null, durationMs, payload, null, null);
        e.setOutputText(outputBody);
        publish(e);
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
            // CR-102 운영 실측: Set 을 jsonb payload 에 넣으면 Hypersistence 깊은복사(직렬화→역직렬화)가
            // "byte array cannot be transformed to Json" 으로 깨져 TOOL_USE insert 가 전멸 (CR-090부터 잠복).
            // JSON 네이티브 타입(List)으로 변환해 적재한다.
            payload.put("input_keys", new java.util.ArrayList<>(input.keySet()));
            payload.put("input_preview", truncate(input.toString(), PREVIEW_MAX));
        }
        WorkflowRunEventEntity e = buildEvent(runId, stepId, iteration, EventType.TOOL_USE, toolName, null, payload, null, subagentRunId);
        // CR-102: 도구 input 전문 적재 (절단 없음) — 품질 분석용. 어댑터/SDK 산 Map 타입 방어 차원에서 plain Map 복사.
        if (input != null && !input.isEmpty()) e.setInputJson(new LinkedHashMap<>(input));
        publish(e);
    }

    public void toolResult(UUID runId, String stepId, Integer iteration, String toolName,
                           long durationMs, boolean ok, String error, int outputSize, UUID subagentRunId) {
        toolResult(runId, stepId, iteration, toolName, durationMs, ok, error, outputSize, subagentRunId, null);
    }

    /** CR-102: 도구 결과 본문(outputBody) 전문 적재 — output 이 다음 단계에 제대로 쓰였나 검토용. */
    public void toolResult(UUID runId, String stepId, Integer iteration, String toolName,
                           long durationMs, boolean ok, String error, int outputSize, UUID subagentRunId,
                           String outputBody) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("output_size", outputSize);
        payload.put("ok", ok);
        if (error != null) payload.put("error", truncate(error, 200));
        WorkflowRunEventEntity e = buildEvent(runId, stepId, iteration, EventType.TOOL_RESULT, toolName, durationMs, payload, null, subagentRunId);
        e.setOutputText(outputBody);
        publish(e);
    }

    /**
     * 입력 프롬프트(promptBody) 적재 — LLM 호출 "직전"에 발행한다.
     *
     * <p>입력은 우리가 모델에 보내기 전에 이미 확정한 값이므로, 응답(LLM_RESPONSE)을 기다리지 않고
     * 즉시 적재해 응답 생성 중(running)에도 화면에서 입력을 볼 수 있게 한다. responseText/토큰은 비운다.
     * connectionId 는 payload 메타로만 (LLM_RESPONSE 와 동일 규약).
     */
    public void llmRequest(UUID runId, String stepId, Integer iteration,
                           String model, String promptBody, String connectionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (model != null) payload.put("model", model);
        if (connectionId != null && !connectionId.isBlank()) payload.put("connection_id", connectionId);
        WorkflowRunEventEntity e = buildEvent(runId, stepId, iteration, EventType.LLM_REQUEST, null, null, payload, null, null);
        e.setPromptText(promptBody);
        publish(e);
    }

    public void llmResponse(UUID runId, String stepId, Integer iteration,
                            String model, int inputTokens, int outputTokens,
                            String finishReason, long durationMs,
                            String traceId, UUID subagentRunId) {
        llmResponse(runId, stepId, iteration, model, inputTokens, outputTokens,
                finishReason, durationMs, traceId, subagentRunId, null, null, null);
    }

    public void llmResponse(UUID runId, String stepId, Integer iteration,
                            String model, int inputTokens, int outputTokens,
                            String finishReason, long durationMs,
                            String traceId, UUID subagentRunId,
                            String promptBody, String responseBody) {
        llmResponse(runId, stepId, iteration, model, inputTokens, outputTokens,
                finishReason, durationMs, traceId, subagentRunId, promptBody, responseBody, null);
    }

    /**
     * CR-102: 프롬프트 입력(promptBody) ↔ 응답 본문(responseBody) 전문 적재 — LLM 응답 품질 정독용.
     * connectionId 는 payload 에 메타로만 적재 (UI 가 어떤 커넥터를 썼는지 모델과 함께 표시).
     */
    public void llmResponse(UUID runId, String stepId, Integer iteration,
                            String model, int inputTokens, int outputTokens,
                            String finishReason, long durationMs,
                            String traceId, UUID subagentRunId,
                            String promptBody, String responseBody, String connectionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (model != null) payload.put("model", model);
        if (connectionId != null && !connectionId.isBlank()) payload.put("connection_id", connectionId);
        payload.put("in_tok", inputTokens);
        payload.put("out_tok", outputTokens);
        if (finishReason != null) payload.put("finish_reason", finishReason);
        WorkflowRunEventEntity e = buildEvent(runId, stepId, iteration, EventType.LLM_RESPONSE, null, durationMs, payload, traceId, subagentRunId);
        e.setPromptText(promptBody);
        e.setResponseText(responseBody);
        publish(e);
    }

    /**
     * CR-102: CLI 어댑터 내부 도구 루프 관찰({@code LLMResponse.observedToolEvents})을
     * TOOL_USE/TOOL_RESULT 이벤트로 일괄 적재. iteration 은 관찰 순서.
     * tool_result 미페어링(output null) 건은 TOOL_USE 만 적재.
     */
    public void observedTools(UUID runId, String stepId, UUID subagentRunId,
                              java.util.List<com.platform.llm.model.ObservedToolEvent> events) {
        if (events == null || events.isEmpty()) return;
        int i = 0;
        for (com.platform.llm.model.ObservedToolEvent ev : events) {
            toolUse(runId, stepId, i, ev.toolName(), ev.input(), subagentRunId);
            if (ev.output() != null) {
                toolResult(runId, stepId, i, ev.toolName(),
                        ev.durationMs() != null ? ev.durationMs() : 0L,
                        true, null, ev.output().length(), subagentRunId, ev.output());
            }
            i++;
        }
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
