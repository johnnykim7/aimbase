package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * CR-034 PRD-228: 에이전트 간 메시지.
 */
@Entity
@Table(name = "agent_messages", indexes = {
        @Index(name = "idx_agent_messages_session", columnList = "session_id"),
        @Index(name = "idx_agent_messages_to_agent", columnList = "to_agent_id, is_read"),
        @Index(name = "idx_agent_messages_from_agent", columnList = "from_agent_id")
})
public class AgentMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "session_id", length = 100, nullable = false)
    private String sessionId;

    @Column(name = "from_agent_id", length = 100, nullable = false)
    private String fromAgentId;

    @Column(name = "to_agent_id", length = 100, nullable = false)
    private String toAgentId;

    @Column(name = "message_type", length = 30, nullable = false)
    private String messageType = "TEXT";

    @Column(columnDefinition = "text", nullable = false)
    private String content;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // --- Getters & Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getFromAgentId() { return fromAgentId; }
    public void setFromAgentId(String fromAgentId) { this.fromAgentId = fromAgentId; }

    public String getToAgentId() { return toAgentId; }
    public void setToAgentId(String toAgentId) { this.toAgentId = toAgentId; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public boolean isRead() { return isRead; }
    public void setRead(boolean read) { isRead = read; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
