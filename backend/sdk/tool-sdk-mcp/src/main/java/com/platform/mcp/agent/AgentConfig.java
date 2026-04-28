package com.platform.mcp.agent;

/**
 * CR-041: Agent 설정.
 * MCP 서버 포트, Aimbase 서버 주소, STUN 서버, 하트비트 간격 등.
 *
 * <p>CR-074: TURN-TCP NAT 우회 — turnEnabled / turnTransport / runnerPort 추가.
 * turnEnabled=true 일 때 {@link AgentLifecycle} 이 부팅 시 TCP allocate 후
 * relay 주소를 {@code metadata.runnerEndpoint} 로 등록하고
 * {@link TurnConnectionBindHandler} + {@link TurnLoopbackBridge} 를 함께 가동한다.
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
        String turnSharedSecret,
        // CR-074
        boolean turnEnabled,
        TurnTransport turnTransport,
        int runnerPort,
        /** RFC 5766 §9 — CreatePermission 으로 등록할 외부 client IP 화이트리스트 (콤마 구분). */
        String turnAllowedPeerIps
) {
    public enum TurnTransport { UDP, TCP }

    /** 기본값 적용 빌더 패턴 대체 */
    public AgentConfig(String agentName, String aimbaseUrl, String apiKey, int mcpPort, String workspaceBase) {
        this(agentName, aimbaseUrl, apiKey, mcpPort, workspaceBase,
                60_000L, "59.8.160.12", 3478,
                "59.8.160.12", 3478, "turnpike.local",
                "e1e1df7f0e394f4c601ca620ff0b4032998cb95373b7abc47d5271cf1bde4bab",
                false, TurnTransport.TCP, 8290, null);
    }

    /** 최소 설정 (기본 워크스페이스) */
    public AgentConfig(String agentName, String aimbaseUrl, String apiKey, int mcpPort) {
        this(agentName, aimbaseUrl, apiKey, mcpPort, null);
    }

    /** STUN만 지정 (TURN 기본값 사용, TURN 비활성) */
    public AgentConfig(String agentName, String aimbaseUrl, String apiKey, int mcpPort,
                       String workspaceBase, long heartbeatIntervalMs,
                       String stunServer, int stunPort) {
        this(agentName, aimbaseUrl, apiKey, mcpPort, workspaceBase,
                heartbeatIntervalMs, stunServer, stunPort,
                "59.8.160.12", 3478, "turnpike.local",
                "e1e1df7f0e394f4c601ca620ff0b4032998cb95373b7abc47d5271cf1bde4bab",
                false, TurnTransport.TCP, 8290, null);
    }

    /** 12-필드 후방호환 — TURN 비활성 기본값. */
    public AgentConfig(String agentName, String aimbaseUrl, String apiKey, int mcpPort,
                       String workspaceBase, long heartbeatIntervalMs,
                       String stunServer, int stunPort,
                       String turnServer, int turnPort,
                       String turnRealm, String turnSharedSecret) {
        this(agentName, aimbaseUrl, apiKey, mcpPort, workspaceBase,
                heartbeatIntervalMs, stunServer, stunPort,
                turnServer, turnPort, turnRealm, turnSharedSecret,
                false, TurnTransport.TCP, 8290, null);
    }

    /** 15-필드 후방호환 (CR-074 1차) — peer IP 화이트리스트 비어있음. */
    public AgentConfig(String agentName, String aimbaseUrl, String apiKey, int mcpPort,
                       String workspaceBase, long heartbeatIntervalMs,
                       String stunServer, int stunPort,
                       String turnServer, int turnPort,
                       String turnRealm, String turnSharedSecret,
                       boolean turnEnabled, TurnTransport turnTransport, int runnerPort) {
        this(agentName, aimbaseUrl, apiKey, mcpPort, workspaceBase,
                heartbeatIntervalMs, stunServer, stunPort,
                turnServer, turnPort, turnRealm, turnSharedSecret,
                turnEnabled, turnTransport, runnerPort, null);
    }
}
