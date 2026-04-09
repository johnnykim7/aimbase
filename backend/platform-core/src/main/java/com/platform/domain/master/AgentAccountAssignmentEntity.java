package com.platform.domain.master;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * 에이전트 계정 할당 엔티티 (Master DB).
 * 테넌트/앱과 에이전트 계정 간의 매핑을 관리한다.
 */
@Entity
@Table(name = "agent_account_assignments",
        uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "tenant_id", "app_id"}))
public class AgentAccountAssignmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private AgentAccountEntity account;

    @Column(name = "tenant_id", length = 100)
    private String tenantId;

    @Column(name = "app_id", length = 100)
    private String appId;

    @Column(name = "assignment_type", length = 20, nullable = false)
    private String assignmentType = "fixed";

    @Column(nullable = false)
    private Integer priority = 0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // ── Getters & Setters ──

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public AgentAccountEntity getAccount() { return account; }
    public void setAccount(AgentAccountEntity account) { this.account = account; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }

    public String getAssignmentType() { return assignmentType; }
    public void setAssignmentType(String assignmentType) { this.assignmentType = assignmentType; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
