package com.platform.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "response_cache")
public class ResponseCacheEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "cache_key", length = 64, unique = true, nullable = false)
    private String cacheKey;

    @Column(length = 100, nullable = false)
    private String model;

    @Column(name = "user_message", columnDefinition = "text", nullable = false)
    private String userMessage;

    @Column(name = "response_text", columnDefinition = "text", nullable = false)
    private String responseText;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "hit_count", nullable = false)
    private int hitCount = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    // ── Getters & Setters ──
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCacheKey() { return cacheKey; }
    public void setCacheKey(String cacheKey) { this.cacheKey = cacheKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }
    public String getResponseText() { return responseText; }
    public void setResponseText(String responseText) { this.responseText = responseText; }
    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }
    public int getHitCount() { return hitCount; }
    public void setHitCount(int hitCount) { this.hitCount = hitCount; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
}
