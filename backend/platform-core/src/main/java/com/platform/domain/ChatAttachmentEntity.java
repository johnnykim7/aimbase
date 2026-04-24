package com.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "chat_attachments")
public class ChatAttachmentEntity {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "session_id", length = 100, nullable = false)
    private String sessionId;

    @Column(length = 255, nullable = false)
    private String filename;

    @Column(name = "media_type", length = 100, nullable = false)
    private String mediaType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_path", length = 500, nullable = false)
    private String storagePath;

    @Column
    private Integer pages;

    @Column(length = 64, nullable = false)
    private String checksum;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }

    public Integer getPages() { return pages; }
    public void setPages(Integer pages) { this.pages = pages; }

    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
}
