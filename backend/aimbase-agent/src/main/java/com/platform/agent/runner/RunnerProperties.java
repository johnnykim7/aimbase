package com.platform.agent.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CR-071 Phase 2: ClaudeCliRunner HTTP 서비스 설정.
 *
 * <p>application.yml 의 {@code aimbase.runner.*} 또는 {@code --runner-mode} 진입 시
 * 시스템 프로퍼티({@code aimbase.runner.api-key} 등) 로 주입된다.
 */
@ConfigurationProperties(prefix = "aimbase.runner")
public class RunnerProperties {

    /** Runner HTTP 서비스 활성화 여부. {@code --runner-mode} 진입 시 true 로 설정됨. */
    private boolean enabled = false;

    /** Runner 인증 API Key. 호출자(Aimbase 서버 ClaudeCliAdapter) 가 X-Api-Key 헤더로 송신. */
    private String apiKey;

    /** 기본 Claude CLI 모델 (connection.config.model 미지정 시 폴백). */
    private String defaultModel = "claude-sonnet-4-5";

    /** Claude CLI 바이너리 경로 (없으면 PATH 의 'claude'). */
    private String claudeBinary = "claude";

    /** 최대 동시 워커 수 (BIZ-100). */
    private int maxWorkers = 5;

    /** CR-106: CLI turn 타임아웃(초). 장기 AGENT_CALL(다수 PDF 적재) 대응 설정화. 기본 300s. */
    private int turnTimeoutSeconds = 300;

    /** Aimbase MCP 서버 jar 경로 (AIMBASE/HYBRID tool-mode 일 때). */
    private String aimbaseMcpJar;

    /** CR-072: 서버 측 MCP endpoint 호출용 base URL. 비어있으면 미박음 (aimbase-server 항목 안 들어감). */
    private String serverMcpBaseUrl;
    /** CR-072: 서버 측 MCP endpoint 인증용 API Key. */
    private String serverMcpApiKey;
    /** CR-072: 서버 측 MCP endpoint 라우팅용 agent-id. */
    private String serverMcpAgentId;

    /**
     * 외부 MCP 서버 추가 주입 (예: playwright). mcpServers 내부 객체 JSON.
     *
     * <p>예: {@code aimbase.runner.extra-mcp-servers-json={"playwright":{"command":"npx","args":["-y","@playwright/mcp@latest"]}}}
     *
     * <p>브라우저 조작처럼 Runner 가 도는 PC 에서만 의미 있는 도구를 붙일 때 쓴다.
     * aimbase 항목과 병합되며 키가 겹치면 이 값이 이긴다.
     */
    private String extraMcpServersJson;

    /**
     * CR-126: AIMBASE 모드에서 봉인할 CLI built-in 도구 목록 (CSV).
     *
     * <p>비어있으면 {@code ClaudeCliCommandBuilder.DEFAULT_SEALED_NATIVE_TOOLS} 를 쓴다.
     * CLI 버전업으로 새 built-in 이 추가되면 이 설정으로 재배포 없이 봉인 범위를 넓힐 수 있다.
     * 예: {@code aimbase.runner.sealed-native-tools=Bash,Edit,Write,NewTool}
     */
    private String sealedNativeTools;

    /** CR-121: 좀비 reaper sweep 주기(초). 0 이하면 reaper 비활성. 기본 300s(5분). */
    private int reaperIntervalSeconds = 300;
    /** CR-121: idle 임계(초) — 마지막 turn 활동 후 이 시간 넘게 놀고 있는 워커를 회수. 기본 900s(15분). */
    private int reaperIdleThresholdSeconds = 900;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    public String getClaudeBinary() { return claudeBinary; }
    public void setClaudeBinary(String claudeBinary) { this.claudeBinary = claudeBinary; }
    public int getMaxWorkers() { return maxWorkers; }
    public void setMaxWorkers(int maxWorkers) { this.maxWorkers = maxWorkers; }
    public int getTurnTimeoutSeconds() { return turnTimeoutSeconds; }
    public void setTurnTimeoutSeconds(int turnTimeoutSeconds) { this.turnTimeoutSeconds = turnTimeoutSeconds; }
    public String getAimbaseMcpJar() { return aimbaseMcpJar; }
    public void setAimbaseMcpJar(String aimbaseMcpJar) { this.aimbaseMcpJar = aimbaseMcpJar; }

    public String getServerMcpBaseUrl() { return serverMcpBaseUrl; }
    public void setServerMcpBaseUrl(String serverMcpBaseUrl) { this.serverMcpBaseUrl = serverMcpBaseUrl; }

    public String getServerMcpApiKey() { return serverMcpApiKey; }
    public void setServerMcpApiKey(String serverMcpApiKey) { this.serverMcpApiKey = serverMcpApiKey; }

    public String getServerMcpAgentId() { return serverMcpAgentId; }
    public void setServerMcpAgentId(String serverMcpAgentId) { this.serverMcpAgentId = serverMcpAgentId; }

    public String getExtraMcpServersJson() { return extraMcpServersJson; }
    public void setExtraMcpServersJson(String extraMcpServersJson) { this.extraMcpServersJson = extraMcpServersJson; }

    public String getSealedNativeTools() { return sealedNativeTools; }
    public void setSealedNativeTools(String sealedNativeTools) { this.sealedNativeTools = sealedNativeTools; }

    /**
     * CR-126: CSV 설정을 목록으로 파싱. 미설정/공백이면 null → 빌더 기본 상수 폴백.
     */
    public java.util.List<String> resolveSealedNativeTools() {
        if (sealedNativeTools == null || sealedNativeTools.isBlank()) return null;
        java.util.List<String> parsed = java.util.Arrays.stream(sealedNativeTools.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return parsed.isEmpty() ? null : parsed;
    }

    public int getReaperIntervalSeconds() { return reaperIntervalSeconds; }
    public void setReaperIntervalSeconds(int reaperIntervalSeconds) { this.reaperIntervalSeconds = reaperIntervalSeconds; }

    public int getReaperIdleThresholdSeconds() { return reaperIdleThresholdSeconds; }
    public void setReaperIdleThresholdSeconds(int reaperIdleThresholdSeconds) { this.reaperIdleThresholdSeconds = reaperIdleThresholdSeconds; }
}
