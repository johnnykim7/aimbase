package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * CR-029: 도메인 앱 기본 설정.
 */
@Entity
@Table(name = "domain_app_config")
public class DomainAppConfigEntity {

    @Id
    @Column(length = 100)
    private String id;

    @Column(name = "domain_app", nullable = false, unique = true, length = 50)
    private String domainApp;

    @Column(name = "default_context_recipe_id", length = 100)
    private String defaultContextRecipeId;

    @Type(JsonBinaryType.class)
    @Column(name = "default_tool_allowlist", columnDefinition = "jsonb")
    private List<String> defaultToolAllowlist;

    @Type(JsonBinaryType.class)
    @Column(name = "default_tool_denylist", columnDefinition = "jsonb")
    private List<String> defaultToolDenylist;

    @Type(JsonBinaryType.class)
    @Column(name = "default_policy_preset", columnDefinition = "jsonb")
    private Map<String, Object> defaultPolicyPreset;

    @Column(name = "default_session_scope", length = 20)
    private String defaultSessionScope = "project";

    @Column(name = "default_runtime", length = 20)
    private String defaultRuntime = "llm_api";

    @Type(JsonBinaryType.class)
    @Column(name = "mcp_server_ids", columnDefinition = "jsonb")
    private List<String> mcpServerIds;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (updatedAt == null) updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // --- Getters & Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDomainApp() { return domainApp; }
    public void setDomainApp(String domainApp) { this.domainApp = domainApp; }

    public String getDefaultContextRecipeId() { return defaultContextRecipeId; }
    public void setDefaultContextRecipeId(String defaultContextRecipeId) { this.defaultContextRecipeId = defaultContextRecipeId; }

    public List<String> getDefaultToolAllowlist() { return defaultToolAllowlist; }
    public void setDefaultToolAllowlist(List<String> defaultToolAllowlist) { this.defaultToolAllowlist = defaultToolAllowlist; }

    public List<String> getDefaultToolDenylist() { return defaultToolDenylist; }
    public void setDefaultToolDenylist(List<String> defaultToolDenylist) { this.defaultToolDenylist = defaultToolDenylist; }

    public Map<String, Object> getDefaultPolicyPreset() { return defaultPolicyPreset; }
    public void setDefaultPolicyPreset(Map<String, Object> defaultPolicyPreset) { this.defaultPolicyPreset = defaultPolicyPreset; }

    public String getDefaultSessionScope() { return defaultSessionScope; }
    public void setDefaultSessionScope(String defaultSessionScope) { this.defaultSessionScope = defaultSessionScope; }

    public String getDefaultRuntime() { return defaultRuntime; }
    public void setDefaultRuntime(String defaultRuntime) { this.defaultRuntime = defaultRuntime; }

    public List<String> getMcpServerIds() { return mcpServerIds; }
    public void setMcpServerIds(List<String> mcpServerIds) { this.mcpServerIds = mcpServerIds; }

    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
