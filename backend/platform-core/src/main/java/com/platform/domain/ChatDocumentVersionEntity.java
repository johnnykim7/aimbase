package com.platform.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * CR-181 BIZ-200: 문서의 판. 1판은 처음 고치기 직전의 원본이다.
 * BLOCK 은 본문을 이 행에, FILE 은 파일 사본을 저장소에 둔다. 작업 폴더 out/ 은 24시간 뒤 지워지고
 * 같은 이름으로 덮어쓰면 앞 판이 사라지기 때문이다.
 */
@Entity
@Table(name = "chat_document_versions")
public class ChatDocumentVersionEntity {

    public enum MadeBy { ORIGINAL, CHAT_EDIT, RESTORE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", columnDefinition = "uuid", nullable = false)
    private UUID documentId;

    @Column(nullable = false)
    private int version;

    @Column(name = "base_version")
    private Integer baseVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "made_by", length = 20, nullable = false)
    private MadeBy madeBy;

    @Column(name = "turn_id", columnDefinition = "uuid")
    private UUID turnId;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "storage_path", length = 500)
    private String storagePath;

    @Column(name = "source_storage_path", length = 500)
    private String sourceStoragePath;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(length = 64, nullable = false)
    private String sha256;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Integer getBaseVersion() { return baseVersion; }
    public void setBaseVersion(Integer baseVersion) { this.baseVersion = baseVersion; }
    public MadeBy getMadeBy() { return madeBy; }
    public void setMadeBy(MadeBy madeBy) { this.madeBy = madeBy; }
    public UUID getTurnId() { return turnId; }
    public void setTurnId(UUID turnId) { this.turnId = turnId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public String getSourceStoragePath() { return sourceStoragePath; }
    public void setSourceStoragePath(String sourceStoragePath) { this.sourceStoragePath = sourceStoragePath; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
