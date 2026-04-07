package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * CR-038 PRD-248: 세션 브리핑 엔티티.
 * 세션 이전 작업 요약을 캐시한다. Tenant DB에 저장.
 */
@Entity
@Table(name = "session_briefs")
public class SessionBriefEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "session_id", length = 255, nullable = false)
    private String sessionId;

    @Column(columnDefinition = "text", nullable = false)
    private String summary;

    @Type(JsonBinaryType.class)
    @Column(name = "key_decisions", columnDefinition = "jsonb")
    private List<String> keyDecisions;

    @Type(JsonBinaryType.class)
    @Column(name = "pending_items", columnDefinition = "jsonb")
    private List<String> pendingItems;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "model_used", length = 100)
    private String modelUsed;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // --- Getters / Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<String> getKeyDecisions() { return keyDecisions; }
    public void setKeyDecisions(List<String> keyDecisions) { this.keyDecisions = keyDecisions; }

    public List<String> getPendingItems() { return pendingItems; }
    public void setPendingItems(List<String> pendingItems) { this.pendingItems = pendingItems; }

    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }

    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public Map<String, Object> toMap() {
        return Map.of(
                "session_id", sessionId,
                "summary", summary,
                "key_decisions", keyDecisions != null ? keyDecisions : List.of(),
                "pending_items", pendingItems != null ? pendingItems : List.of(),
                "message_count", messageCount,
                "model_used", modelUsed != null ? modelUsed : "",
                "created_at", createdAt.toString()
        );
    }
}
