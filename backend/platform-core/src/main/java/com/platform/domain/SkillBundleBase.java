package com.platform.domain;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * CR-147: 스킬 묶음. 테넌트 전용(skill_bundles)과 플랫폼 공용(platform_skill_bundles)이 같은 모양이라
 * 칸은 여기 두고 테이블만 하위 엔티티가 정한다.
 *
 * <p>id 를 직접 넣으므로 Persistable 로 새 행임을 알린다. 그러지 않으면 save 가 merge 로 돌아
 * 넣기 전에 SELECT 를 한 번 더 친다.</p>
 */
@MappedSuperclass
public abstract class SkillBundleBase implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(length = 64, nullable = false, unique = true)
    private String name;

    @Column(columnDefinition = "text", nullable = false)
    private String description;

    @Column(name = "latest_version", nullable = false)
    private int latestVersion;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Transient
    private boolean newEntity = true;

    @PostLoad
    @PostPersist
    void markPersisted() { this.newEntity = false; }

    @PreUpdate
    void onUpdate() { this.updatedAt = OffsetDateTime.now(); }

    @Override
    public boolean isNew() { return newEntity; }

    @Override
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getLatestVersion() { return latestVersion; }
    public void setLatestVersion(int latestVersion) { this.latestVersion = latestVersion; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
