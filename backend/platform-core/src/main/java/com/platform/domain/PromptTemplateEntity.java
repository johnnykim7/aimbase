package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-036 PRD-249: 프롬프트 템플릿 외부화 엔티티.
 */
@Entity
@Table(name = "prompt_templates")
public class PromptTemplateEntity {

    @EmbeddedId
    private PromptTemplateEntityId pk;

    @Column(length = 50, nullable = false)
    private String category;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text", nullable = false)
    private String template;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> variables;

    @Column(length = 10, nullable = false)
    private String language = "en";

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "is_system", nullable = false)
    private boolean isSystem = false;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // --- Getters / Setters ---

    public PromptTemplateEntityId getPk() { return pk; }
    public void setPk(PromptTemplateEntityId pk) { this.pk = pk; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }

    public List<Map<String, Object>> getVariables() { return variables; }
    public void setVariables(List<Map<String, Object>> variables) { this.variables = variables; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    public boolean isSystem() { return isSystem; }
    public void setSystem(boolean system) { isSystem = system; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", pk != null ? pk.getKey() : null);
        m.put("version", pk != null ? pk.getVersion() : null);
        m.put("category", category);
        m.put("name", name);
        m.put("description", description);
        m.put("template", template);
        m.put("variables", variables);
        m.put("language", language);
        m.put("is_active", isActive);
        m.put("is_system", isSystem);
        m.put("created_by", createdBy);
        m.put("created_at", createdAt);
        m.put("updated_at", updatedAt);
        return m;
    }
}
