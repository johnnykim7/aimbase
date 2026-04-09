package com.platform.domain.master;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 에이전트 계정 엔티티 (Master DB).
 * CLI 에이전트(Claude Code, Codex 등)의 OAuth/API Key 계정을 관리한다.
 * CLAUDE_CONFIG_DIR 방식으로 계정별 설정 디렉토리를 격리한다.
 */
@Entity
@Table(name = "agent_accounts")
public class AgentAccountEntity {

    @Id
    @Column(length = 100)
    private String id;

    @Column(length = 200, nullable = false)
    private String name;

    @Column(name = "agent_type", length = 50, nullable = false)
    private String agentType;

    @Column(name = "auth_type", length = 20, nullable = false)
    private String authType = "oauth";

    @Column(name = "container_host", length = 255, nullable = false)
    private String containerHost;

    @Column(name = "container_port", nullable = false)
    private Integer containerPort = 9100;

    @Column(length = 20, nullable = false)
    private String status = "active";

    @Column(nullable = false)
    private Integer priority = 0;

    @Column(name = "max_concurrent", nullable = false)
    private Integer maxConcurrent = 1;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> config = Map.of();

    @Column(name = "health_status", length = 20)
    private String healthStatus = "unknown";

    @Column(name = "last_health_at")
    private OffsetDateTime lastHealthAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    // ── Getters & Setters ──

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAgentType() { return agentType; }
    public void setAgentType(String agentType) { this.agentType = agentType; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public String getContainerHost() { return containerHost; }
    public void setContainerHost(String containerHost) { this.containerHost = containerHost; }

    public Integer getContainerPort() { return containerPort; }
    public void setContainerPort(Integer containerPort) { this.containerPort = containerPort; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public Integer getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(Integer maxConcurrent) { this.maxConcurrent = maxConcurrent; }

    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }

    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }

    public OffsetDateTime getLastHealthAt() { return lastHealthAt; }
    public void setLastHealthAt(OffsetDateTime lastHealthAt) { this.lastHealthAt = lastHealthAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    /** 사이드카 base URL */
    public String getBaseUrl() {
        return "http://" + containerHost + ":" + containerPort;
    }
}
