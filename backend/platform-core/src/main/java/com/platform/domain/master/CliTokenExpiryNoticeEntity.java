package com.platform.domain.master;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/** CR-196 BIZ-284: 1년 토큰 만료 알림을 보낸 기록. 같은 기한과 문턱에 두 번 보내지 않는다. */
@Entity
@Table(name = "cli_token_expiry_notices")
public class CliTokenExpiryNoticeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", length = 100, nullable = false)
    private String tenantId;

    @Column(name = "agent_name", length = 200, nullable = false)
    private String agentName;

    @Column(name = "config_dir", length = 500, nullable = false)
    private String configDir = "";

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "threshold_days", nullable = false)
    private int thresholdDays;

    @Column(length = 50, nullable = false)
    private String channels;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getConfigDir() { return configDir; }
    public void setConfigDir(String configDir) { this.configDir = configDir; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public int getThresholdDays() { return thresholdDays; }
    public void setThresholdDays(int thresholdDays) { this.thresholdDays = thresholdDays; }
    public String getChannels() { return channels; }
    public void setChannels(String channels) { this.channels = channels; }
    public OffsetDateTime getSentAt() { return sentAt; }
    public void setSentAt(OffsetDateTime sentAt) { this.sentAt = sentAt; }
}
