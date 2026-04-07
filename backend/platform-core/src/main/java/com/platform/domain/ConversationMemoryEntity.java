package com.platform.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "conversation_memories")
public class ConversationMemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(name = "user_id", length = 100)
    private String userId;

    @Column(name = "memory_type", length = 30, nullable = false)
    private String memoryType; // SYSTEM_RULES, LONG_TERM, SHORT_TERM, USER_PROFILE

    @Column(columnDefinition = "text", nullable = false)
    private String content;

    /** 메모리 범위 (PRD-199): PRIVATE / TEAM / GLOBAL */
    @Column(name = "scope", length = 20, nullable = false)
    private String scope = "PRIVATE";

    /** 팀 ID — TEAM scope 전용 (PRD-200) */
    @Column(name = "team_id", length = 100)
    private String teamId;

    @Column(name = "relevance_score")
    private Double relevanceScore;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = OffsetDateTime.now(); }

    // ── Getters & Setters ──
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getMemoryType() { return memoryType; }
    public void setMemoryType(String memoryType) { this.memoryType = memoryType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }
    public Double getRelevanceScore() { return relevanceScore; }
    public void setRelevanceScore(Double relevanceScore) { this.relevanceScore = relevanceScore; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
