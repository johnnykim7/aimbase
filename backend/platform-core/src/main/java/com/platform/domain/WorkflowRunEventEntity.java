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
 * 얇은 버전 — prompt/response 원본은 저장 안 함. 깊이 분석은 {@code trace_id}/{@code subagent_run_id} 로 join.
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
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
