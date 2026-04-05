package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * CR-029: 도구 실행 이력 — lineage 추적 및 비교 가능성.
 */
@Entity
@Table(name = "tool_execution_log")
public class ToolExecutionLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    @Column(name = "workflow_run_id", length = 100)
    private String workflowRunId;

    @Column(name = "step_id", length = 100)
    private String stepId;

    @Column(name = "turn_number")
    private Integer turnNumber;

    @Column(name = "sequence_in_turn")
    private Integer sequenceInTurn;

    @Column(name = "tool_id", nullable = false, length = 100)
    private String toolId;

    @Column(name = "tool_name", nullable = false, length = 100)
    private String toolName;

    @Column(name = "input_summary", columnDefinition = "TEXT")
    private String inputSummary;

    @Type(JsonBinaryType.class)
    @Column(name = "input_full", columnDefinition = "jsonb")
    private Map<String, Object> inputFull;

    @Column(name = "output_summary", columnDefinition = "TEXT")
    private String outputSummary;

    @Column(name = "output_full", columnDefinition = "TEXT")
    private String outputFull;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> artifacts;

    @Type(JsonBinaryType.class)
    @Column(name = "side_effects", columnDefinition = "jsonb")
    private Map<String, Object> sideEffects;

    @Type(JsonBinaryType.class)
    @Column(name = "context_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> contextSnapshot;

    @Column(name = "runtime_kind", length = 20)
    private String runtimeKind;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    // --- Getters & Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(String workflowRunId) { this.workflowRunId = workflowRunId; }

    public String getStepId() { return stepId; }
    public void setStepId(String stepId) { this.stepId = stepId; }

    public Integer getTurnNumber() { return turnNumber; }
    public void setTurnNumber(Integer turnNumber) { this.turnNumber = turnNumber; }

    public Integer getSequenceInTurn() { return sequenceInTurn; }
    public void setSequenceInTurn(Integer sequenceInTurn) { this.sequenceInTurn = sequenceInTurn; }

    public String getToolId() { return toolId; }
    public void setToolId(String toolId) { this.toolId = toolId; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getInputSummary() { return inputSummary; }
    public void setInputSummary(String inputSummary) { this.inputSummary = inputSummary; }

    public Map<String, Object> getInputFull() { return inputFull; }
    public void setInputFull(Map<String, Object> inputFull) { this.inputFull = inputFull; }

    public String getOutputSummary() { return outputSummary; }
    public void setOutputSummary(String outputSummary) { this.outputSummary = outputSummary; }

    public String getOutputFull() { return outputFull; }
    public void setOutputFull(String outputFull) { this.outputFull = outputFull; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }

    public Map<String, Object> getArtifacts() { return artifacts; }
    public void setArtifacts(Map<String, Object> artifacts) { this.artifacts = artifacts; }

    public Map<String, Object> getSideEffects() { return sideEffects; }
    public void setSideEffects(Map<String, Object> sideEffects) { this.sideEffects = sideEffects; }

    public Map<String, Object> getContextSnapshot() { return contextSnapshot; }
    public void setContextSnapshot(Map<String, Object> contextSnapshot) { this.contextSnapshot = contextSnapshot; }

    public String getRuntimeKind() { return runtimeKind; }
    public void setRuntimeKind(String runtimeKind) { this.runtimeKind = runtimeKind; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
