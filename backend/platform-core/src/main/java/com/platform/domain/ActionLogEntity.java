package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "action_logs")
public class ActionLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workflow_run_id")
    private UUID workflowRunId;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(length = 100, nullable = false)
    private String intent;

    @Column(length = 20, nullable = false)
    private String type;

    @Column(length = 50, nullable = false)
    private String adapter;

    @Column(length = 200)
    private String destination;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Type(JsonBinaryType.class)
    @Column(name = "policy_result", columnDefinition = "jsonb")
    private Map<String, Object> policyResult;

    @Column(length = 20, nullable = false)
    private String status;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> error;

    @Column(name = "executed_at")
    private OffsetDateTime executedAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public UUID getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(UUID workflowRunId) { this.workflowRunId = workflowRunId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getAdapter() { return adapter; }
    public void setAdapter(String adapter) { this.adapter = adapter; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public Map<String, Object> getPolicyResult() { return policyResult; }
    public void setPolicyResult(Map<String, Object> policyResult) { this.policyResult = policyResult; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Map<String, Object> getResult() { return result; }
    public void setResult(Map<String, Object> result) { this.result = result; }
    public Map<String, Object> getError() { return error; }
    public void setError(Map<String, Object> error) { this.error = error; }
    public OffsetDateTime getExecutedAt() { return executedAt; }
}
