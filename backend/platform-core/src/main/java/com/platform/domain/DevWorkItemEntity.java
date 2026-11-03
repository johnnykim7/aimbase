package com.platform.domain;

import com.platform.devwork.DevWorkStatus;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** CR-164: 개발 작업 하나의 지금 상태(PRD-350). 키는 부르는 쪽이 정한 작업 번호다. */
@Entity
@Table(name = "dev_work_items")
public class DevWorkItemEntity {

    @Id
    @Column(name = "work_item_id", length = 100)
    private String workItemId;

    @Column(name = "feature_name", nullable = false, length = 300)
    private String featureName;

    @Column(nullable = false, length = 50)
    private String stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DevWorkStatus status;

    @Column(name = "evidence_url", length = 2000)
    private String evidenceUrl;

    @Column(name = "status_changed_at", nullable = false)
    private OffsetDateTime statusChangedAt;

    @Column(name = "last_workflow_run_id")
    private UUID lastWorkflowRunId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }
    public String getFeatureName() { return featureName; }
    public void setFeatureName(String featureName) { this.featureName = featureName; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public DevWorkStatus getStatus() { return status; }
    public void setStatus(DevWorkStatus status) { this.status = status; }
    public String getEvidenceUrl() { return evidenceUrl; }
    public void setEvidenceUrl(String evidenceUrl) { this.evidenceUrl = evidenceUrl; }
    public OffsetDateTime getStatusChangedAt() { return statusChangedAt; }
    public void setStatusChangedAt(OffsetDateTime statusChangedAt) { this.statusChangedAt = statusChangedAt; }
    public UUID getLastWorkflowRunId() { return lastWorkflowRunId; }
    public void setLastWorkflowRunId(UUID lastWorkflowRunId) { this.lastWorkflowRunId = lastWorkflowRunId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
