package com.platform.domain;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * CR-182 BIZ-212: 공유 링크 한 개. 사본 폴더 이름이 id 라서 저장 전에 id 를 정한다.
 * 그래서 Persistable 로 새 행임을 알려 저장 때 불필요한 조회와 병합을 피한다.
 * 세션을 지워도 끊은 기록을 남기려고 대화 테이블에 외래키를 두지 않는다.
 */
@Entity
@Table(name = "chat_shares")
public class ChatShareEntity implements Persistable<UUID> {

    public enum Kind { CONVERSATION, FILE }

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(length = 64, nullable = false, unique = true)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private Kind kind;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "snapshot_path", length = 500, nullable = false)
    private String snapshotPath;

    @Column(name = "snapshot_size", nullable = false)
    private long snapshotSize;

    @Column(name = "content_type", length = 150, nullable = false)
    private String contentType;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "view_count", nullable = false)
    private int viewCount;

    @Column(name = "last_viewed_at")
    private OffsetDateTime lastViewedAt;

    @Column(name = "files_purged_at")
    private OffsetDateTime filesPurgedAt;

    @Transient
    private boolean fresh = true;

    @PostLoad
    @PostPersist
    void markStored() {
        this.fresh = false;
    }

    @Override
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    @Override
    public boolean isNew() { return fresh; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getSnapshotPath() { return snapshotPath; }
    public void setSnapshotPath(String snapshotPath) { this.snapshotPath = snapshotPath; }
    public long getSnapshotSize() { return snapshotSize; }
    public void setSnapshotSize(long snapshotSize) { this.snapshotSize = snapshotSize; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
    public int getViewCount() { return viewCount; }
    public OffsetDateTime getLastViewedAt() { return lastViewedAt; }
    public OffsetDateTime getFilesPurgedAt() { return filesPurgedAt; }
    public void setFilesPurgedAt(OffsetDateTime filesPurgedAt) { this.filesPurgedAt = filesPurgedAt; }
}
