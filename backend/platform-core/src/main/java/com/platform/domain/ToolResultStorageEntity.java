package com.platform.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * CR-048 PRD-301: 대형 tool result 외부 저장.
 * 임계치(81920B) 초과한 도구 결과의 원본을 보관하고, 체인에는 ref stub만 주입한다.
 */
@Entity
@Table(name = "tool_result_storage")
public class ToolResultStorageEntity {

    @Id
    @Column(name = "result_id", length = 64, nullable = false)
    private String resultId;

    @Column(name = "session_id", length = 100, nullable = false)
    private String sessionId;

    @Column(name = "tool_name", length = 100, nullable = false)
    private String toolName;

    @Column(name = "full_content", columnDefinition = "text", nullable = false)
    private String fullContent;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    public ToolResultStorageEntity() {}

    public ToolResultStorageEntity(String resultId, String sessionId, String toolName,
                                    String fullContent, String summary, int sizeBytes,
                                    OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        this.resultId = resultId;
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.fullContent = fullContent;
        this.summary = summary;
        this.sizeBytes = sizeBytes;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getResultId() { return resultId; }
    public void setResultId(String resultId) { this.resultId = resultId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getFullContent() { return fullContent; }
    public void setFullContent(String fullContent) { this.fullContent = fullContent; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public int getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(int sizeBytes) { this.sizeBytes = sizeBytes; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
}
