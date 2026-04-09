package com.platform.mcp.agent;

/**
 * CR-041: Agent 설정.
 * MCP 서버 포트, Aimbase 서버 주소, STUN 서버, 하트비트 간격 등.
 */
public record AgentConfig(
        String agentName,
        String aimbaseUrl,
        String apiKey,
        int mcpPort,
        String workspaceBase,
        long heartbeatIntervalMs,
        String stunServer,
        int stunPort
) {
    /** 기본값 적용 빌더 패턴 대체 */
    public AgentConfig(String agentName, String aimbaseUrl, String apiKey, int mcpPort, String workspaceBase) {
        this(agentName, aimbaseUrl, apiKey, mcpPort, workspaceBase,
                60_000L, "stun.l.google.com", 19302);
    }

    /** 최소 설정 (기본 워크스페이스) */
    public AgentConfig(String agentName, String aimbaseUrl, String apiKey, int mcpPort) {
        this(agentName, aimbaseUrl, apiKey, mcpPort, null);
    }
}
