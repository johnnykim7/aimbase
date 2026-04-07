package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * CR-035 PRD-237: 스킬 엔티티.
 * 재사용 가능한 프롬프트 + 도구 조합 경량 실행 단위.
 * Tenant DB에 저장.
 */
@Entity
@Table(name = "skills")
public class SkillEntity {

    @Id
    @Column(length = 100)
    private String id;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "system_prompt", columnDefinition = "text", nullable = false)
    private String systemPrompt;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private List<String> tools;

    @Type(JsonBinaryType.class)
    @Column(name = "output_schema", columnDefinition = "jsonb")
    private Map<String, Object> outputSchema;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private List<String> tags;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
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

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public List<String> getTools() { return tools; }
    public void setTools(List<String> tools) { this.tools = tools; }

    public Map<String, Object> getOutputSchema() { return outputSchema; }
    public void setOutputSchema(Map<String, Object> outputSchema) { this.outputSchema = outputSchema; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public Map<String, Object> toMap() {
        return Map.of(
                "id", id,
                "name", name,
                "description", description != null ? description : "",
                "system_prompt", systemPrompt,
                "tools", tools != null ? tools : List.of(),
                "tags", tags != null ? tags : List.of(),
                "is_active", isActive
        );
    }
}
