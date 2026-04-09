package com.platform.domain.master;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 플랫폼 공용 워크플로우.
 * Master DB에 저장되며 모든 테넌트가 실행 가능.
 */
@Entity
@Table(name = "platform_workflows")
public class PlatformWorkflowEntity {

    @Id
    @Column(length = 100)
    private String id;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(length = 50)
    private String category;

    @Type(JsonBinaryType.class)
    @Column(name = "trigger_config", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> triggerConfig;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> steps;

    @Type(JsonBinaryType.class)
    @Column(name = "error_handling", columnDefinition = "jsonb")
    private Map<String, Object> errorHandling;

    @Type(JsonBinaryType.class)
    @Column(name = "output_schema", columnDefinition = "jsonb")
    private Map<String, Object> outputSchema;

    @Type(JsonBinaryType.class)
    @Column(name = "input_schema", columnDefinition = "jsonb")
    private Map<String, Object> inputSchema;

    @Column(name = "is_active")
    private boolean isActive = true;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // --- Getters / Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public Map<String, Object> getTriggerConfig() { return triggerConfig; }
    public void setTriggerConfig(Map<String, Object> triggerConfig) { this.triggerConfig = triggerConfig; }

    public List<Map<String, Object>> getSteps() { return steps; }
    public void setSteps(List<Map<String, Object>> steps) { this.steps = steps; }

    public Map<String, Object> getErrorHandling() { return errorHandling; }
    public void setErrorHandling(Map<String, Object> errorHandling) { this.errorHandling = errorHandling; }

    public Map<String, Object> getOutputSchema() { return outputSchema; }
    public void setOutputSchema(Map<String, Object> outputSchema) { this.outputSchema = outputSchema; }

    public Map<String, Object> getInputSchema() { return inputSchema; }
    public void setInputSchema(Map<String, Object> inputSchema) { this.inputSchema = inputSchema; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
