package com.platform.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * CR-181 BIZ-200: 위젯 옆 창에 표시한 문서 중 대화로 고친 적이 있는 것. 처음 고칠 때 만든다.
 * CR-180 이 문서를 가리키는 값(파일은 세션과 이름, 코드 블록은 답 번호와 순번)으로 찾는다.
 * 세션을 지워도 남기려고 외래키를 두지 않는다.
 */
@Entity
@Table(name = "chat_documents")
public class ChatDocumentEntity {

    public enum Kind { FILE, BLOCK }

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "session_id", length = 100, nullable = false)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(length = 10, nullable = false)
    private Kind kind;

    @Column(length = 255)
    private String name;

    @Column(name = "origin_turn_id", columnDefinition = "uuid")
    private UUID originTurnId;

    @Column(name = "block_index")
    private Integer blockIndex;

    @Column(length = 10)
    private String lang;

    @Column(name = "latest_version", nullable = false)
    private int latestVersion;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public UUID getOriginTurnId() { return originTurnId; }
    public void setOriginTurnId(UUID originTurnId) { this.originTurnId = originTurnId; }
    public Integer getBlockIndex() { return blockIndex; }
    public void setBlockIndex(Integer blockIndex) { this.blockIndex = blockIndex; }
    public String getLang() { return lang; }
    public void setLang(String lang) { this.lang = lang; }
    public int getLatestVersion() { return latestVersion; }
    public void setLatestVersion(int latestVersion) { this.latestVersion = latestVersion; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
