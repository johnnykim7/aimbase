package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "workflow_runs")
public class WorkflowRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workflow_id", length = 100)
    private String workflowId;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(length = 20, nullable = false)
    private String status;

    @Column(name = "current_step", length = 100)
    private String currentStep;

    @Type(JsonBinaryType.class)
    @Column(name = "step_results", columnDefinition = "jsonb")
    private Map<String, Object> stepResults;

    @Type(JsonBinaryType.class)
    @Column(name = "input_data", columnDefinition = "jsonb")
    private Map<String, Object> inputData;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> error;

    @Column(name = "started_at")
    private OffsetDateTime startedAt = OffsetDateTime.now();

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /** CR-058: 서브워크플로우 실행 시 부모 run 참조. NULL = 최상위. */
    @Column(name = "parent_run_id")
    private UUID parentRunId;

    /** CR-058: 부모 런에서 이 자식을 트리거한 스텝 ID. */
    @Column(name = "parent_step_id", length = 255)
    private String parentStepId;

    /**
     * CR-084 P4: cyclic 워크플로우가 HUMAN_INPUT 에서 중단될 때 보존하는 재개 상태.
     * {@code {"worklist": [...], "executed": N}}. NULL = DAG 모드 또는 cyclic 미중단.
     */
    @Type(JsonBinaryType.class)
    @Column(name = "pending_worklist", columnDefinition = "jsonb")
    private Map<String, Object> pendingWorklist;

    public UUID getId() { return id; }
    /** CR-058: 테스트/SubWorkflowStepExecutor 용 setter — JPA 가 UUID 자동 발급을 지원하나 명시 설정이 필요한 경우 대비. */
    public void setId(UUID id) { this.id = id; }
    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCurrentStep() { return currentStep; }
    public void setCurrentStep(String currentStep) { this.currentStep = currentStep; }
    public Map<String, Object> getStepResults() { return stepResults; }
    public void setStepResults(Map<String, Object> stepResults) { this.stepResults = stepResults; }
    public Map<String, Object> getInputData() { return inputData; }
    public void setInputData(Map<String, Object> inputData) { this.inputData = inputData; }
    public Map<String, Object> getError() { return error; }
    public void setError(Map<String, Object> error) { this.error = error; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
    public UUID getParentRunId() { return parentRunId; }
    public void setParentRunId(UUID parentRunId) { this.parentRunId = parentRunId; }
    public String getParentStepId() { return parentStepId; }
    public void setParentStepId(String parentStepId) { this.parentStepId = parentStepId; }
    public Map<String, Object> getPendingWorklist() { return pendingWorklist; }
    public void setPendingWorklist(Map<String, Object> pendingWorklist) { this.pendingWorklist = pendingWorklist; }
}
