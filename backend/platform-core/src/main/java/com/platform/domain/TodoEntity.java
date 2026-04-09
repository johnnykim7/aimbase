package com.platform.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * CR-033 PRD-225: 세션 체크리스트 엔티티.
 * LLM이 TodoWriteTool로 관리하는 작업 항목.
 */
@Entity
@Table(name = "todos", indexes = {
        @Index(name = "idx_todos_session_id", columnList = "session_id")
})
public class TodoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "session_id", length = 255, nullable = false)
    private String sessionId;

    @Column(columnDefinition = "text", nullable = false)
    private String content;

    @Column(name = "active_form", columnDefinition = "text")
    private String activeForm;

    @Column(length = 50, nullable = false)
    private String status = "pending";  // pending, in_progress, completed

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    // --- Getters & Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getActiveForm() { return activeForm; }
    public void setActiveForm(String activeForm) { this.activeForm = activeForm; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; this.updatedAt = OffsetDateTime.now(); }

    public int getOrderIndex() { return orderIndex; }
    public void setOrderIndex(int orderIndex) { this.orderIndex = orderIndex; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public Map<String, Object> toMap() {
        return Map.of(
                "content", content,
                "status", status,
                "activeForm", activeForm != null ? activeForm : ""
        );
    }
}
