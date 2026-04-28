package com.platform.runner.claudecli;

import java.time.Duration;

/**
 * CR-050 Phase 3 (PRD-308) — CR-071 Phase 1 에서 cli-runner 모듈로 이동(2026-04-27).
 * Claude CLI 어댑터/Runner 설정 (plain POJO).
 *
 * <p>이 클래스는 Spring 의존을 가지지 않는다. 호출처(예: ClaudeCliRunner 본체 또는
 * Phase 4 의 ClaudeCliAdapter) 가 application 설정에서 값을 읽어 setter 로 주입하거나,
 * 빌더처럼 직접 인스턴스를 구성해 사용한다.
 *
 * <p>Phase 9 결정: aimbase-agent jar 경로를 직접 박지 않고 환경변수 또는 호출처가 채워주는 형태로 둔다.
 */
public class ClaudeCliAdapterConfig {

    private boolean enabled = false;
    private int timeoutSeconds = 300;
    private int maxWorkersPerRun = 5;
    private int acquireTimeoutSeconds = 60;
    private String cliBinaryPath = "claude";
    /**
     * CR-069: CLI 도구 노출 모드 (Worker / Runner default).
     * 값: aimbase | native | hybrid (대소문자 무관)
     */
    private String toolMode = "aimbase";
    /**
     * Phase 9: --mcp-config 로 CLI 에 주입할 JSON. 비어있으면 도구 미연결 모드 (순수 텍스트 LLM_CALL).
     * 예시: {"mcpServers":{"aimbase":{"command":"java","args":["-jar","/path/to/aimbase-agent.jar","--mcp-stdio"]}}}
     */
    private String mcpConfigJson = "";
    /** Phase 9: aimbase-agent jar 경로. 미지정 시 mcpConfigJson 직접 설정 필요. */
    private String aimbaseAgentJar = "";
    /** Phase 9: aimbase-agent 자식 프로세스 spawn 시 사용할 java 실행 경로 (Java 21 필수). */
    private String javaCommand = "java";
    /** CR-072: 서버 측 MCP endpoint 노출 시 mcpServers 의 "aimbase-server" 항목으로 박을 base URL. 비어있으면 미박음. */
    private String serverMcpBaseUrl = "";
    /** CR-072: aimbase-server MCP endpoint 호출용 X-API-Key 헤더 값. */
    private String serverMcpApiKey = "";
    /** CR-072: aimbase-server MCP endpoint 호출용 X-Aimbase-Agent-Id 헤더 값. */
    private String serverMcpAgentId = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }

    public int getMaxWorkersPerRun() { return maxWorkersPerRun; }
    public void setMaxWorkersPerRun(int v) { this.maxWorkersPerRun = v; }

    public int getAcquireTimeoutSeconds() { return acquireTimeoutSeconds; }
    public void setAcquireTimeoutSeconds(int v) { this.acquireTimeoutSeconds = v; }

    public String getCliBinaryPath() { return cliBinaryPath; }
    public void setCliBinaryPath(String v) { this.cliBinaryPath = v; }

    public String getToolMode() { return toolMode; }
    public void setToolMode(String v) { this.toolMode = (v == null || v.isBlank()) ? "aimbase" : v; }

    /**
     * CR-069: 문자열 toolMode 를 enum 으로 변환. 알 수 없는 값이면 AIMBASE 폴백 (보안 default).
     */
    public ClaudeCliCommandBuilder.ToolMode resolveToolMode() {
        try {
            return ClaudeCliCommandBuilder.ToolMode.valueOf(toolMode.trim().toUpperCase());
        } catch (Exception e) {
            return ClaudeCliCommandBuilder.ToolMode.AIMBASE;
        }
    }

    public String getMcpConfigJson() { return mcpConfigJson; }
    public void setMcpConfigJson(String v) { this.mcpConfigJson = v; }

    public String getAimbaseAgentJar() { return aimbaseAgentJar; }
    public void setAimbaseAgentJar(String v) { this.aimbaseAgentJar = v; }

    public String getJavaCommand() { return javaCommand; }
    public void setJavaCommand(String v) { this.javaCommand = v; }

    public String getServerMcpBaseUrl() { return serverMcpBaseUrl; }
    public void setServerMcpBaseUrl(String v) { this.serverMcpBaseUrl = (v == null) ? "" : v; }

    public String getServerMcpApiKey() { return serverMcpApiKey; }
    public void setServerMcpApiKey(String v) { this.serverMcpApiKey = (v == null) ? "" : v; }

    public String getServerMcpAgentId() { return serverMcpAgentId; }
    public void setServerMcpAgentId(String v) { this.serverMcpAgentId = (v == null) ? "" : v; }

    public Duration turnTimeout() { return Duration.ofSeconds(timeoutSeconds); }

    /**
     * 효과적인 MCP 설정 JSON 을 반환.
     *
     * <p>우선순위:
     * <ol>
     *   <li>{@code mcpConfigJson} 이 명시되어 있으면 그대로 (호출처가 외부 설정 가공한 경우).</li>
     *   <li>그 외에는 mcpServers 객체를 합성:
     *     <ul>
     *       <li>{@code aimbaseAgentJar} 가 있으면 {@code aimbase-local} (stdio) 박음.</li>
     *       <li>{@code serverMcpBaseUrl} 이 있으면 {@code aimbase-server} (SSE, X-API-Key/X-Aimbase-Agent-Id 헤더) 박음. (CR-072)</li>
     *     </ul>
     *   </li>
     *   <li>둘 다 비어있으면 {@code {"mcpServers":{}}}.</li>
     * </ol>
     * </p>
     */
    public String resolveMcpConfigJson() {
        if (mcpConfigJson != null && !mcpConfigJson.isBlank()) {
            return mcpConfigJson;
        }

        java.util.Map<String, Object> servers = new java.util.LinkedHashMap<>();

        boolean serverExposed = serverMcpBaseUrl != null && !serverMcpBaseUrl.isBlank();
        // 호환 모드: 서버 노출 미사용 시 기존 키 'aimbase' 유지 (CLI 호출 prefix 깨짐 방지).
        // 서버 노출 사용 시에만 'aimbase-local' / 'aimbase-server' 분리.
        String localKey = serverExposed ? "aimbase-local" : "aimbase";

        if (aimbaseAgentJar != null && !aimbaseAgentJar.isBlank()) {
            String javaBin = (javaCommand == null || javaCommand.isBlank()) ? "java" : javaCommand;
            java.util.Map<String, Object> local = new java.util.LinkedHashMap<>();
            local.put("command", javaBin);
            local.put("args", java.util.List.of("-jar", aimbaseAgentJar, "--mcp-stdio"));
            servers.put(localKey, local);
        }

        if (serverExposed) {
            java.util.Map<String, Object> server = new java.util.LinkedHashMap<>();
            // CR-072 (2026-04-28): Claude CLI MCP config schema 가 SSE 트랜스포트에 "type" 필수 요구.
            server.put("type", "sse");
            String base = serverMcpBaseUrl.endsWith("/")
                    ? serverMcpBaseUrl.substring(0, serverMcpBaseUrl.length() - 1)
                    : serverMcpBaseUrl;
            server.put("url", base + "/mcp/sse");
            java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
            if (serverMcpApiKey != null && !serverMcpApiKey.isBlank()) {
                headers.put("X-API-Key", serverMcpApiKey);
            }
            if (serverMcpAgentId != null && !serverMcpAgentId.isBlank()) {
                headers.put("X-Aimbase-Agent-Id", serverMcpAgentId);
            }
            if (!headers.isEmpty()) {
                server.put("headers", headers);
            }
            servers.put("aimbase-server", server);
        }

        java.util.Map<String, Object> root = java.util.Map.of("mcpServers", servers);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 안전 폴백: 빈 mcpServers
            return "{\"mcpServers\":{}}";
        }
    }

    /**
     * 이 설정으로 ClaudeCliWorkerPool 을 생성한다.
     * Spring 의존을 제거한 plain factory — 호출처(Phase 4 ClaudeCliAdapter / Runner) 가 직접 호출.
     */
    public ClaudeCliWorkerPool createWorkerPool() {
        Duration turnTimeout = turnTimeout();
        String mcpConfig = resolveMcpConfigJson();
        ClaudeCliWorkerPool.WorkerFactory factory = (model, resumeSessionId, forkSession, configDir) ->
                new ClaudeCliWorker(cliBinaryPath, model, resumeSessionId, forkSession,
                        configDir, turnTimeout, mcpConfig);
        return new ClaudeCliWorkerPool(factory, maxWorkersPerRun,
                Duration.ofSeconds(acquireTimeoutSeconds), resolveToolMode());
    }
}
