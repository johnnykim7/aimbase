package com.platform.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-041: 원격 에이전트 레지스트리 엔티티.
 * 소비앱 로컬에서 MCP 서버로 대기하는 에이전트 정보를 관리한다.
 */
@Entity
@Table(name = "agent_registry")
public class AgentRegistryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_name", length = 200, nullable = false)
    private String agentName;

    @Column(name = "user_id", length = 255)
    private String userId;

    @Column(name = "session_id", length = 255)
    private String sessionId;

    @Column(name = "public_address", length = 255, nullable = false)
    private String publicAddress;

    @Column(name = "mcp_port", nullable = false)
    private int mcpPort;

    @Column(name = "turn_relay_address", length = 255)
    private String turnRelayAddress;

    @Column(length = 20, nullable = false)
    private String status = "ACTIVE";

    @Type(JsonBinaryType.class)
    @Column(name = "tools_cache", columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> toolsCache = List.of();

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata = Map.of();

    @Column(name = "registered_at")
    private OffsetDateTime registeredAt = OffsetDateTime.now();

    @Column(name = "last_heartbeat_at")
    private OffsetDateTime lastHeartbeatAt = OffsetDateTime.now();

    @Column(name = "deregistered_at")
    private OffsetDateTime deregisteredAt;

    // ── Convenience ──

    /** MCP 서버 접속 URL 생성 (직접 연결) */
    public String getMcpUrl() {
        return "http://" + publicAddress + ":" + mcpPort;
    }

    /** TURN 릴레이 경유 MCP URL (turnRelayAddress가 "IP:port" 형식) */
    public String getTurnMcpUrl() {
        if (turnRelayAddress == null || turnRelayAddress.isBlank()) return null;
        return "http://" + turnRelayAddress;
    }

    // ── Getters / Setters ──

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getPublicAddress() { return publicAddress; }
    public void setPublicAddress(String publicAddress) { this.publicAddress = publicAddress; }

    public int getMcpPort() { return mcpPort; }
    public void setMcpPort(int mcpPort) { this.mcpPort = mcpPort; }

    public String getTurnRelayAddress() { return turnRelayAddress; }
    public void setTurnRelayAddress(String turnRelayAddress) { this.turnRelayAddress = turnRelayAddress; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<Map<String, Object>> getToolsCache() { return toolsCache; }
    public void setToolsCache(List<Map<String, Object>> toolsCache) { this.toolsCache = toolsCache; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public OffsetDateTime getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(OffsetDateTime registeredAt) { this.registeredAt = registeredAt; }

    public OffsetDateTime getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(OffsetDateTime lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }

    public OffsetDateTime getDeregisteredAt() { return deregisteredAt; }
    public void setDeregisteredAt(OffsetDateTime deregisteredAt) { this.deregisteredAt = deregisteredAt; }
}
