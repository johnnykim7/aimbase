package com.platform.domain.master;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "subscriptions")
public class SubscriptionEntity {

    @Id
    @Column(name = "tenant_id", length = 100)
    private String tenantId;

    @Column(length = 50, nullable = false)
    private String plan = "free"; // free, starter, pro, enterprise

    @Column(name = "llm_monthly_token_quota", nullable = false)
    private Long llmMonthlyTokenQuota = 1_000_000L;

    @Column(name = "max_connections", nullable = false)
    private Integer maxConnections = 5;

    @Column(name = "max_knowledge_sources", nullable = false)
    private Integer maxKnowledgeSources = 3;

    @Column(name = "max_workflows", nullable = false)
    private Integer maxWorkflows = 10;

    @Column(name = "storage_gb", nullable = false)
    private Integer storageGb = 1;

    @Column(name = "max_users", nullable = false)
    private Integer maxUsers = 5;

    @Column(name = "valid_from")
    private OffsetDateTime validFrom = OffsetDateTime.now();

    @Column(name = "valid_until")
    private OffsetDateTime validUntil;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }
    public Long getLlmMonthlyTokenQuota() { return llmMonthlyTokenQuota; }
    public void setLlmMonthlyTokenQuota(Long llmMonthlyTokenQuota) { this.llmMonthlyTokenQuota = llmMonthlyTokenQuota; }
    public Integer getMaxConnections() { return maxConnections; }
    public void setMaxConnections(Integer maxConnections) { this.maxConnections = maxConnections; }
    public Integer getMaxKnowledgeSources() { return maxKnowledgeSources; }
    public void setMaxKnowledgeSources(Integer maxKnowledgeSources) { this.maxKnowledgeSources = maxKnowledgeSources; }
    public Integer getMaxWorkflows() { return maxWorkflows; }
    public void setMaxWorkflows(Integer maxWorkflows) { this.maxWorkflows = maxWorkflows; }
    public Integer getStorageGb() { return storageGb; }
    public void setStorageGb(Integer storageGb) { this.storageGb = storageGb; }
    public Integer getMaxUsers() { return maxUsers; }
    public void setMaxUsers(Integer maxUsers) { this.maxUsers = maxUsers; }
    public OffsetDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(OffsetDateTime validFrom) { this.validFrom = validFrom; }
    public OffsetDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(OffsetDateTime validUntil) { this.validUntil = validUntil; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
