package com.platform.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * CR-042: Agent 설정 프로퍼티.
 * application.yml의 agent.* 프로퍼티를 바인딩한다.
 */
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** 에이전트 이름 (Aimbase 등록 시 식별자) */
    private String name = "aimbase-agent";

    /** Aimbase 서버 URL (필수) */
    private String aimbaseUrl;

    /** Aimbase API Key (필수) */
    private String apiKey;

    /** MCP 서버 포트 */
    private int mcpPort = 8190;

    /** 워크스페이스 루트 경로 (비어있으면 ~/aimbase-workspace) */
    private String workspacePath;

    /** 하트비트 간격 (ms) */
    private long heartbeatIntervalMs = 60_000;

    /** STUN 서버 주소 */
    private String stunServer = "stun.l.google.com";

    /** STUN 서버 포트 */
    private int stunPort = 19302;

    /** 비활성화할 도구 이름 목록 */
    private List<String> disabledTools = new ArrayList<>();

    // ── getters / setters ──

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAimbaseUrl() { return aimbaseUrl; }
    public void setAimbaseUrl(String aimbaseUrl) { this.aimbaseUrl = aimbaseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public int getMcpPort() { return mcpPort; }
    public void setMcpPort(int mcpPort) { this.mcpPort = mcpPort; }

    public String getWorkspacePath() { return workspacePath; }
    public void setWorkspacePath(String workspacePath) { this.workspacePath = workspacePath; }

    public long getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) { this.heartbeatIntervalMs = heartbeatIntervalMs; }

    public String getStunServer() { return stunServer; }
    public void setStunServer(String stunServer) { this.stunServer = stunServer; }

    public int getStunPort() { return stunPort; }
    public void setStunPort(int stunPort) { this.stunPort = stunPort; }

    public List<String> getDisabledTools() { return disabledTools; }
    public void setDisabledTools(List<String> disabledTools) { this.disabledTools = disabledTools; }
}
