package com.platform.domain.master;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "tenant_usage_summary")
public class TenantUsageSummaryEntity {

    @EmbeddedId
    private TenantUsageSummaryEntityId pk;

    @Column(name = "total_input_tokens", nullable = false)
    private Long totalInputTokens = 0L;

    @Column(name = "total_output_tokens", nullable = false)
    private Long totalOutputTokens = 0L;

    @Column(name = "storage_used_mb", nullable = false)
    private Long storageUsedMb = 0L;

    @Column(name = "api_call_count", nullable = false)
    private Long apiCallCount = 0L;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public TenantUsageSummaryEntityId getPk() { return pk; }
    public void setPk(TenantUsageSummaryEntityId pk) { this.pk = pk; }
    public Long getTotalInputTokens() { return totalInputTokens; }
    public void setTotalInputTokens(Long totalInputTokens) { this.totalInputTokens = totalInputTokens; }
    public Long getTotalOutputTokens() { return totalOutputTokens; }
    public void setTotalOutputTokens(Long totalOutputTokens) { this.totalOutputTokens = totalOutputTokens; }
    public Long getStorageUsedMb() { return storageUsedMb; }
    public void setStorageUsedMb(Long storageUsedMb) { this.storageUsedMb = storageUsedMb; }
    public Long getApiCallCount() { return apiCallCount; }
    public void setApiCallCount(Long apiCallCount) { this.apiCallCount = apiCallCount; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
