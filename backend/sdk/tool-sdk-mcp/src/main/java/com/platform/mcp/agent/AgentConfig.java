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
        int stunPort,
        String turnServer,
        int turnPort,
        String turnRealm,
        String turnSharedSecret
) {
    /** 기본값 적용 빌더 패턴 대체 */
    public AgentConfig(String agentName, String aimbaseUrl, String apiKey, int mcpPort, String workspaceBase) {
        this(agentName, aimbaseUrl, apiKey, mcpPort, workspaceBase,
                60_000L, "14.63.25.49", 3478,
                "14.63.25.49", 3478, "turnpike.local",
                "e1e1df7f0e394f4c601ca620ff0b4032998cb95373b7abc47d5271cf1bde4bab");
    }

    /** 최소 설정 (기본 워크스페이스) */
    public AgentConfig(String agentName, String aimbaseUrl, String apiKey, int mcpPort) {
        this(agentName, aimbaseUrl, apiKey, mcpPort, null);
    }

    /** STUN만 지정 (TURN 기본값 사용) */
    public AgentConfig(String agentName, String aimbaseUrl, String apiKey, int mcpPort,
                       String workspaceBase, long heartbeatIntervalMs,
                       String stunServer, int stunPort) {
        this(agentName, aimbaseUrl, apiKey, mcpPort, workspaceBase,
                heartbeatIntervalMs, stunServer, stunPort,
                "14.63.25.49", 3478, "turnpike.local",
                "e1e1df7f0e394f4c601ca620ff0b4032998cb95373b7abc47d5271cf1bde4bab");
    }
}
