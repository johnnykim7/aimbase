package com.platform.domain.master;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** CR-196 PRD-647: 테넌트의 서버 실행기 계정 폴더 하나의 1년 토큰 상태. 토큰 값은 두지 않는다(BIZ-289). */
@Entity
@Table(name = "cli_account_tokens")
public class CliAccountTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", length = 100, nullable = false)
    private String tenantId;

    @Column(name = "agent_name", length = 200, nullable = false)
    private String agentName;

    @Column(name = "config_dir", length = 500, nullable = false)
    private String configDir = "";

    @Column(name = "managed_by", length = 20, nullable = false)
    private String managedBy = "tenant";

    @Column(nullable = false)
    private boolean present;

    @Column(name = "issued_at")
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(nullable = false)
    private boolean reachable = true;

    @Type(JsonBinaryType.class)
    @Column(name = "connection_ids", columnDefinition = "jsonb", nullable = false)
    private List<String> connectionIds = new ArrayList<>();

    @Column(name = "checked_at", nullable = false)
    private OffsetDateTime checkedAt;

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getConfigDir() { return configDir; }
    public void setConfigDir(String configDir) { this.configDir = configDir; }
    public String getManagedBy() { return managedBy; }
    public void setManagedBy(String managedBy) { this.managedBy = managedBy; }
    public boolean isPresent() { return present; }
    public void setPresent(boolean present) { this.present = present; }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(OffsetDateTime issuedAt) { this.issuedAt = issuedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public boolean isReachable() { return reachable; }
    public void setReachable(boolean reachable) { this.reachable = reachable; }
    public List<String> getConnectionIds() { return connectionIds; }
    public void setConnectionIds(List<String> connectionIds) { this.connectionIds = connectionIds; }
    public OffsetDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(OffsetDateTime checkedAt) { this.checkedAt = checkedAt; }
}
