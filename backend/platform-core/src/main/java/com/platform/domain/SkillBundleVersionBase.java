package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-147: 스킬 묶음의 판. 올릴 때마다 하나 생기고 고치거나 지우지 않는다(BIZ-119).
 */
@MappedSuperclass
public abstract class SkillBundleVersionBase implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "bundle_id", nullable = false)
    private UUID bundleId;

    @Column(nullable = false)
    private int version;

    @Column(columnDefinition = "text", nullable = false)
    private String description;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> frontmatter;

    @Column(columnDefinition = "text", nullable = false)
    private String instructions;

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "total_bytes", nullable = false)
    private long totalBytes;

    @Column(name = "content_sha256", length = 64, nullable = false)
    private String contentSha256;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> warnings = new ArrayList<>();

    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Transient
    private boolean newEntity = true;

    @PostLoad
    @PostPersist
    void markPersisted() { this.newEntity = false; }

    @Override
    public boolean isNew() { return newEntity; }

    @Override
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getBundleId() { return bundleId; }
    public void setBundleId(UUID bundleId) { this.bundleId = bundleId; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, Object> getFrontmatter() { return frontmatter; }
    public void setFrontmatter(Map<String, Object> frontmatter) { this.frontmatter = frontmatter; }

    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }

    public int getFileCount() { return fileCount; }
    public void setFileCount(int fileCount) { this.fileCount = fileCount; }

    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }

    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }

    public List<Map<String, Object>> getWarnings() { return warnings; }
    public void setWarnings(List<Map<String, Object>> warnings) { this.warnings = warnings; }

    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
}
