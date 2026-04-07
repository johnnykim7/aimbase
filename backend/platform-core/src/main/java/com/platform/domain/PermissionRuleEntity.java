package com.platform.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * CR-030 PRD-197: 권한 규칙 엔티티.
 *
 * 도구명 패턴(정규식) → 최소 PermissionLevel 매핑.
 * PermissionClassifier가 AUTO 모드에서 이 규칙을 기반으로 권한을 분류.
 */
@Entity
@Table(name = "permission_rules",
        indexes = @Index(name = "idx_permission_rules_priority", columnList = "priority DESC"))
public class PermissionRuleEntity {

    @Id
    @Column(length = 100)
    private String id;

    /** 규칙 이름 (관리용) */
    @Column(length = 200, nullable = false)
    private String name;

    /** 도구명 매칭 패턴 (Java 정규식). 예: "File.*", ".*Edit.*", "Bash" */
    @Column(name = "tool_name_pattern", length = 500, nullable = false)
    private String toolNamePattern;

    /** 이 패턴에 매칭되는 도구의 최소 요구 권한 */
    @Column(name = "required_level", length = 30, nullable = false)
    private String requiredLevel;

    /** 우선순위 (높을수록 먼저 적용). 동일 도구에 여러 규칙 매칭 시 최고 우선순위 사용. */
    @Column(nullable = false)
    private int priority = 0;

    @Column(name = "is_active")
    private boolean isActive = true;

    /** 규칙 설명 */
    @Column(length = 500)
    private String description;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // ── Getters / Setters ──

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getToolNamePattern() { return toolNamePattern; }
    public void setToolNamePattern(String toolNamePattern) { this.toolNamePattern = toolNamePattern; }
    public String getRequiredLevel() { return requiredLevel; }
    public void setRequiredLevel(String requiredLevel) { this.requiredLevel = requiredLevel; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
