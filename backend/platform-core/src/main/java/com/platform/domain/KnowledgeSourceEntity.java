package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "knowledge_sources")
public class KnowledgeSourceEntity {

    @Id
    @Column(length = 100)
    private String id;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(length = 30, nullable = false)
    private String type;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> config;

    @Type(JsonBinaryType.class)
    @Column(name = "chunking_config", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> chunkingConfig;

    @Type(JsonBinaryType.class)
    @Column(name = "embedding_config", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> embeddingConfig;

    @Type(JsonBinaryType.class)
    @Column(name = "sync_config", columnDefinition = "jsonb")
    private Map<String, Object> syncConfig;

    @Column(length = 20)
    private String status = "idle";

    @Column(name = "document_count")
    private int documentCount = 0;

    @Column(name = "chunk_count")
    private int chunkCount = 0;

    @Column(name = "last_synced_at")
    private OffsetDateTime lastSyncedAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
    public Map<String, Object> getChunkingConfig() { return chunkingConfig; }
    public void setChunkingConfig(Map<String, Object> chunkingConfig) { this.chunkingConfig = chunkingConfig; }
    public Map<String, Object> getEmbeddingConfig() { return embeddingConfig; }
    public void setEmbeddingConfig(Map<String, Object> embeddingConfig) { this.embeddingConfig = embeddingConfig; }
    public Map<String, Object> getSyncConfig() { return syncConfig; }
    public void setSyncConfig(Map<String, Object> syncConfig) { this.syncConfig = syncConfig; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getDocumentCount() { return documentCount; }
    public void setDocumentCount(int documentCount) { this.documentCount = documentCount; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
    public OffsetDateTime getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(OffsetDateTime lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
