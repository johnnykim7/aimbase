package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "prompts")
public class PromptEntity {

    @EmbeddedId
    private PromptEntityId pk;

    @Column(length = 50)
    private String domain;

    @Column(length = 30, nullable = false)
    private String type;

    @Column(columnDefinition = "text", nullable = false)
    private String template;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> variables;

    @Column(name = "is_active")
    private boolean isActive = false;

    @Type(JsonBinaryType.class)
    @Column(name = "ab_test", columnDefinition = "jsonb")
    private Map<String, Object> abTest;

    /** CR-022: 리소스 생성자 (users.id) */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public PromptEntityId getPk() { return pk; }
    public void setPk(PromptEntityId pk) { this.pk = pk; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTemplate() { return template; }
    public void setTemplate(String template) { this.template = template; }
    public List<Map<String, Object>> getVariables() { return variables; }
    public void setVariables(List<Map<String, Object>> variables) { this.variables = variables; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public Map<String, Object> getAbTest() { return abTest; }
    public void setAbTest(Map<String, Object> abTest) { this.abTest = abTest; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
