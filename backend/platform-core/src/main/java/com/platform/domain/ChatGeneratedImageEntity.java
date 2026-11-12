package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** CR-184: 위젯에서 만든 그림 한 장(PRD-540, BIZ-231). */
@Entity
@Table(name = "chat_generated_images")
public class ChatGeneratedImageEntity {

    public static final String SOURCE_GENERATED = "generated";
    public static final String SOURCE_ATTACHMENT = "attachment";

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "session_id", length = 100, nullable = false)
    private String sessionId;

    @Column(name = "turn_id", columnDefinition = "uuid")
    private UUID turnId;

    @Column(name = "connection_id", length = 100, nullable = false)
    private String connectionId;

    @Column(length = 100, nullable = false)
    private String model;

    @Column(columnDefinition = "text", nullable = false)
    private String prompt;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> sources = new ArrayList<>();

    @Column(name = "storage_path", length = 500, nullable = false)
    private String storagePath;

    @Column(name = "media_type", length = 50, nullable = false)
    private String mediaType;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public UUID getTurnId() { return turnId; }
    public void setTurnId(UUID turnId) { this.turnId = turnId; }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public List<Map<String, Object>> getSources() { return sources; }
    public void setSources(List<Map<String, Object>> sources) { this.sources = sources; }

    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }

    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }

    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }

    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
}
