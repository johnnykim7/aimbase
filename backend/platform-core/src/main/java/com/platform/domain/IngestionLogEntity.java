package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "ingestion_logs")
public class IngestionLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_id", length = 100)
    private String sourceId;

    @Column(length = 20, nullable = false)
    private String mode;

    @Column(length = 20, nullable = false)
    private String status;

    @Column(name = "documents_processed")
    private int documentsProcessed = 0;

    @Column(name = "chunks_created")
    private int chunksCreated = 0;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> errors;

    @Column(name = "started_at")
    private OffsetDateTime startedAt = OffsetDateTime.now();

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    public UUID getId() { return id; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getDocumentsProcessed() { return documentsProcessed; }
    public void setDocumentsProcessed(int documentsProcessed) { this.documentsProcessed = documentsProcessed; }
    public int getChunksCreated() { return chunksCreated; }
    public void setChunksCreated(int chunksCreated) { this.chunksCreated = chunksCreated; }
    public List<Map<String, Object>> getErrors() { return errors; }
    public void setErrors(List<Map<String, Object>> errors) { this.errors = errors; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
}
