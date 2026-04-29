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

    /** CR-074: Aimbase tenant id (X-Tenant-Id 헤더로 송신). domain_app 동일 시 명시 필수. */
    private String tenantId;

    /**
     * CR-075: 위젯 토큰 user_ref 와 매칭될 사용자 ID.
     * 등록 페이로드의 userId 로 송신 — Aimbase 가 이 키로 자동 라우팅.
     * 비어있으면 라우팅 자동화 비활성 (헤더 명시만 가능).
     */
    private String userId;

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

    /** CR-074: TURN-TCP NAT 우회 설정 (agent.turn.*) */
    private Turn turn = new Turn();

    public static class Turn {
        /** 활성화 여부 — false 면 기존 공인 IP 직접 등록 흐름 (후방호환) */
        private boolean enabled = false;
        /** 전송 — UDP|TCP. CR-074 정공은 TCP */
        private String transport = "TCP";
        /** TURN 서버 host */
        private String server = "59.8.160.12";
        /** TURN 서버 port */
        private int port = 3478;
        /** 인증 realm */
        private String realm = "turnpike.local";
        /** HMAC-SHA1 long-term credential shared secret */
        private String sharedSecret = "e1e1df7f0e394f4c601ca620ff0b4032998cb95373b7abc47d5271cf1bde4bab";
        /**
         * loopback bridge 의 타깃 포트 — agent 의 RunnerController(Tomcat) 가 listen 하는 포트와 일치해야 한다.
         * 일반적으로 server.port 와 동일하게 설정.
         */
        private int runnerPort = 8290;

        /**
         * RFC 5766 §9 — CreatePermission 으로 등록할 외부 client IP 화이트리스트 (콤마 구분).
         * 이 IP 들에서 오는 incoming TCP 만 ConnectionAttempt → ConnectionBind 로 진입 가능.
         * 비어있으면 CreatePermission 을 보내지 않으므로 외부 connect 가 모두 reject (peer has no permission).
         * 운영: Aimbase BE 의 공인 IP 를 넣어야 함. 예: "59.8.160.12"
         */
        private String allowedPeerIps;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getTransport() { return transport; }
        public void setTransport(String transport) { this.transport = transport; }
        public String getServer() { return server; }
        public void setServer(String server) { this.server = server; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getRealm() { return realm; }
        public void setRealm(String realm) { this.realm = realm; }
        public String getSharedSecret() { return sharedSecret; }
        public void setSharedSecret(String sharedSecret) { this.sharedSecret = sharedSecret; }
        public int getRunnerPort() { return runnerPort; }
        public void setRunnerPort(int runnerPort) { this.runnerPort = runnerPort; }
        public String getAllowedPeerIps() { return allowedPeerIps; }
        public void setAllowedPeerIps(String allowedPeerIps) { this.allowedPeerIps = allowedPeerIps; }
    }

    public Turn getTurn() { return turn; }
    public void setTurn(Turn turn) { this.turn = turn; }

    // ── getters / setters ──

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAimbaseUrl() { return aimbaseUrl; }
    public void setAimbaseUrl(String aimbaseUrl) { this.aimbaseUrl = aimbaseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

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
