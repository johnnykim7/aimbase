package com.platform.domain;

import com.platform.devwork.DevWorkChangeSource;
import com.platform.devwork.DevWorkStatus;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** CR-164: 개발 작업 상태가 바뀐 한 번(BIZ-129). changed_at 이 알림의 reported_at 이다. */
@Entity
@Table(name = "dev_work_item_changes")
public class DevWorkItemChangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "work_item_id", nullable = false, length = 100)
    private String workItemId;

    @Column(nullable = false, length = 50)
    private String stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DevWorkStatus status;

    @Column(name = "evidence_url", length = 2000)
    private String evidenceUrl;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DevWorkChangeSource source;

    @Column(name = "workflow_run_id")
    private UUID workflowRunId;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getWorkItemId() { return workItemId; }
    public void setWorkItemId(String workItemId) { this.workItemId = workItemId; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public DevWorkStatus getStatus() { return status; }
    public void setStatus(DevWorkStatus status) { this.status = status; }
    public String getEvidenceUrl() { return evidenceUrl; }
    public void setEvidenceUrl(String evidenceUrl) { this.evidenceUrl = evidenceUrl; }
    public OffsetDateTime getChangedAt() { return changedAt; }
    public void setChangedAt(OffsetDateTime changedAt) { this.changedAt = changedAt; }
    public DevWorkChangeSource getSource() { return source; }
    public void setSource(DevWorkChangeSource source) { this.source = source; }
    public UUID getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(UUID workflowRunId) { this.workflowRunId = workflowRunId; }
}
