package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "workflows")
public class WorkflowEntity {

    @Id
    @Column(length = 100)
    private String id;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(length = 50)
    private String domain;

    @Type(JsonBinaryType.class)
    @Column(name = "trigger_config", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> triggerConfig;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> steps;

    @Type(JsonBinaryType.class)
    @Column(name = "error_handling", columnDefinition = "jsonb")
    private Map<String, Object> errorHandling;

    /** 워크플로우 입력 JSON Schema. 실행 시 입력 폼 자동 생성. */
    @Type(JsonBinaryType.class)
    @Column(name = "input_schema", columnDefinition = "jsonb")
    private Map<String, Object> inputSchema;

    /** CR-007: 워크플로우 출력 JSON Schema. 마지막 LLM 스텝에 자동 주입. */
    @Type(JsonBinaryType.class)
    @Column(name = "output_schema", columnDefinition = "jsonb")
    private Map<String, Object> outputSchema;

    /** CR-021: 프로젝트 스코핑. null이면 회사 공유. */
    @Column(name = "project_id", length = 100)
    private String projectId;

    /** CR-022: 리소스 생성자 (users.id) */
    @Column(name = "created_by", length = 100)
    private String createdBy;

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

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public Map<String, Object> getTriggerConfig() { return triggerConfig; }
    public void setTriggerConfig(Map<String, Object> triggerConfig) { this.triggerConfig = triggerConfig; }
    public List<Map<String, Object>> getSteps() { return steps; }
    public void setSteps(List<Map<String, Object>> steps) { this.steps = steps; }
    public Map<String, Object> getErrorHandling() { return errorHandling; }
    public void setErrorHandling(Map<String, Object> errorHandling) { this.errorHandling = errorHandling; }
    public Map<String, Object> getInputSchema() { return inputSchema; }
    public void setInputSchema(Map<String, Object> inputSchema) { this.inputSchema = inputSchema; }
    public Map<String, Object> getOutputSchema() { return outputSchema; }
    public void setOutputSchema(Map<String, Object> outputSchema) { this.outputSchema = outputSchema; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
