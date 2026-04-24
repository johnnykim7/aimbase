package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "conversation_messages")
public class ConversationMessageEntity {

    /** CR-049 PRD-303: 메시지 타입 enum 값. */
    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_TOOL_USE = "TOOL_USE";
    public static final String TYPE_TOOL_RESULT = "TOOL_RESULT";
    public static final String TYPE_COMPACT_BOUNDARY = "COMPACT_BOUNDARY";

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "session_id", length = 100, nullable = false)
    private String sessionId;

    @Column(length = 20, nullable = false)
    private String role;

    /** CR-049: TEXT / TOOL_USE / TOOL_RESULT / COMPACT_BOUNDARY */
    @Column(name = "message_type", length = 30, nullable = false)
    private String messageType = TYPE_TEXT;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column
    private int tokens = 0;

    @Column(length = 100)
    private String model;

    /** CR-049: COMPACT_BOUNDARY 전용 메타 {summary, compacted_count, tokens_saved, boundary_at}. */
    @Type(JsonBinaryType.class)
    @Column(name = "boundary_meta", columnDefinition = "jsonb")
    private Map<String, Object> boundaryMeta;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    // CR-046: Soft Delete
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public int getTokens() { return tokens; }
    public void setTokens(int tokens) { this.tokens = tokens; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Map<String, Object> getBoundaryMeta() { return boundaryMeta; }
    public void setBoundaryMeta(Map<String, Object> boundaryMeta) { this.boundaryMeta = boundaryMeta; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
}
