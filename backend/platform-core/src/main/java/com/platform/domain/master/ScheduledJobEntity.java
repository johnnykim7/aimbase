package com.platform.domain.master;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * CR-035 PRD-228: Cron 스케줄 작업.
 * Master DB에 저장. 테넌트별 워크플로우/도구 자동 실행을 관리한다.
 */
@Entity
@Table(name = "scheduled_jobs")
public class ScheduledJobEntity {

    @Id
    @Column(length = 100)
    private String id;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(name = "cron_expression", length = 100, nullable = false)
    private String cronExpression;

    @Column(name = "target_type", length = 20, nullable = false)
    private String targetType; // WORKFLOW | TOOL

    @Column(name = "target_id", length = 100, nullable = false)
    private String targetId;

    @Type(JsonBinaryType.class)
    @Column(name = "input_payload", columnDefinition = "jsonb")
    private Map<String, Object> inputPayload;

    @Column(name = "tenant_id", length = 100, nullable = false)
    private String tenantId;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;

    @Column(name = "next_run_at")
    private OffsetDateTime nextRunAt;

    @Column(name = "last_run_status", length = 20)
    private String lastRunStatus;

    @Column(name = "failure_count", nullable = false)
    private int failureCount = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // --- Getters / Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public Map<String, Object> getInputPayload() { return inputPayload; }
    public void setInputPayload(Map<String, Object> inputPayload) { this.inputPayload = inputPayload; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    public OffsetDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(OffsetDateTime lastRunAt) { this.lastRunAt = lastRunAt; }

    public OffsetDateTime getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(OffsetDateTime nextRunAt) { this.nextRunAt = nextRunAt; }

    public String getLastRunStatus() { return lastRunStatus; }
    public void setLastRunStatus(String lastRunStatus) { this.lastRunStatus = lastRunStatus; }

    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public Map<String, Object> toMap() {
        return Map.of(
                "id", id,
                "name", name,
                "cron_expression", cronExpression,
                "target_type", targetType,
                "target_id", targetId,
                "is_active", isActive,
                "failure_count", failureCount,
                "last_run_status", lastRunStatus != null ? lastRunStatus : "NEVER",
                "last_run_at", lastRunAt != null ? lastRunAt.toString() : "",
                "next_run_at", nextRunAt != null ? nextRunAt.toString() : ""
        );
    }
}
