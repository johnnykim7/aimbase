package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "schemas")
public class SchemaEntity {

    @EmbeddedId
    private SchemaEntityId pk;

    @Column(length = 50)
    private String domain;

    private String description;

    @Type(JsonBinaryType.class)
    @Column(name = "json_schema", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> jsonSchema;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Object transforms;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Object validators;

    /** CR-022: 리소스 생성자 (users.id) */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public SchemaEntityId getPk() { return pk; }
    public void setPk(SchemaEntityId pk) { this.pk = pk; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, Object> getJsonSchema() { return jsonSchema; }
    public void setJsonSchema(Map<String, Object> jsonSchema) { this.jsonSchema = jsonSchema; }
    public Object getTransforms() { return transforms; }
    public void setTransforms(Object transforms) { this.transforms = transforms; }
    public Object getValidators() { return validators; }
    public void setValidators(Object validators) { this.validators = validators; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
