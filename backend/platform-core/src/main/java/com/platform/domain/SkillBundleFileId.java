package com.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * CR-147: 스킬 묶음 파일 키 (판, 묶음 안 경로).
 */
@Embeddable
public class SkillBundleFileId implements Serializable {

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(length = 512, nullable = false)
    private String path;

    protected SkillBundleFileId() {}

    public SkillBundleFileId(UUID versionId, String path) {
        this.versionId = versionId;
        this.path = path;
    }

    public UUID getVersionId() { return versionId; }
    public String getPath() { return path; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SkillBundleFileId that)) return false;
        return Objects.equals(versionId, that.versionId) && Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() { return Objects.hash(versionId, path); }
}
