package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * CR-090: 워크플로우 실행 가시성 이벤트.
 *
 * <p>"어떤 도구를 어떤 input 으로 불렀고 무엇을 받았다" 한 줄 흐름을 시간순으로 재구성하기 위함.
 * 메타(payload preview)는 흐름 재구성용, 본문 컬럼(promptText/responseText/inputJson/outputText)은
 * 품질 분석용 — 절단 없는 전문 적재 (CR-102).
 */
@Entity
@Table(name = "workflow_run_events", indexes = {
        @Index(name = "idx_wfre_run_created", columnList = "run_id, created_at")
})
public class WorkflowRunEventEntity {

    public enum EventType {
        STEP_START,
        TOOL_USE,
        TOOL_RESULT,
        // CR: 입력 프롬프트는 우리가 호출 직전에 손에 들고 있는 값 → 응답을 기다리지 않고 즉시 적재.
        // LLM_RESPONSE 와 분리해 응답 생성 중(running)에도 입력이 보이게 한다 (promptText 만 채움).
        LLM_REQUEST,
        LLM_RESPONSE,
        STEP_END,
        STEP_FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, columnDefinition = "uuid")
    private UUID runId;

    @Column(name = "step_id", length = 100)
    private String stepId;

    @Column(name = "iteration")
    private Integer iteration;

    @Column(name = "event_type", length = 40, nullable = false)
    @Enumerated(EnumType.STRING)
    private EventType eventType;

    @Column(name = "tool_name", length = 100)
    private String toolName;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Type(JsonBinaryType.class)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "trace_id", length = 100)
    private String traceId;

    @Column(name = "subagent_run_id", columnDefinition = "uuid")
    private UUID subagentRunId;

    // CR-102: 품질 분석용 본문 전문 (절단 없음, nullable)
    @Column(name = "prompt_text", columnDefinition = "text")
    private String promptText;        // LLM_RESPONSE: system + prompt 입력

    @Column(name = "response_text", columnDefinition = "text")
    private String responseText;      // LLM_RESPONSE: 응답 본문

    @Type(JsonBinaryType.class)
    @Column(name = "input_json", columnDefinition = "jsonb")
    private Map<String, Object> inputJson;   // TOOL_USE: 도구 input 전문

    @Column(name = "output_text", columnDefinition = "text")
    private String outputText;        // TOOL_RESULT / STEP_END: 결과 본문 전문

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID runId) { this.runId = runId; }
    public String getStepId() { return stepId; }
    public void setStepId(String stepId) { this.stepId = stepId; }
    public Integer getIteration() { return iteration; }
    public void setIteration(Integer iteration) { this.iteration = iteration; }
    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public UUID getSubagentRunId() { return subagentRunId; }
    public void setSubagentRunId(UUID subagentRunId) { this.subagentRunId = subagentRunId; }
    public String getPromptText() { return promptText; }
    public void setPromptText(String promptText) { this.promptText = promptText; }
    public String getResponseText() { return responseText; }
    public void setResponseText(String responseText) { this.responseText = responseText; }
    public Map<String, Object> getInputJson() { return inputJson; }
    public void setInputJson(Map<String, Object> inputJson) { this.inputJson = inputJson; }
    public String getOutputText() { return outputText; }
    public void setOutputText(String outputText) { this.outputText = outputText; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
