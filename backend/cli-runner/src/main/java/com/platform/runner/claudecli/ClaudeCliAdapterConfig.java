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

    public Duration turnTimeout() { return Duration.ofSeconds(timeoutSeconds); }

    /**
     * 효과적인 MCP 설정 JSON 을 반환.
     * mcpConfigJson 이 명시되어 있으면 그대로, 아니면 aimbaseAgentJar 로 자동 합성, 둘 다 비면 빈 설정.
     */
    public String resolveMcpConfigJson() {
        if (mcpConfigJson != null && !mcpConfigJson.isBlank()) {
            return mcpConfigJson;
        }
        if (aimbaseAgentJar != null && !aimbaseAgentJar.isBlank()) {
            // CLI 가 자식 프로세스로 <java-command> -jar <agent> --mcp-stdio 기동
            String java = (javaCommand == null || javaCommand.isBlank()) ? "java" : javaCommand;
            return "{\"mcpServers\":{\"aimbase\":{\"command\":\""
                    + java.replace("\"", "\\\"") + "\","
                    + "\"args\":[\"-jar\",\"" + aimbaseAgentJar.replace("\"", "\\\"")
                    + "\",\"--mcp-stdio\"]}}}";
        }
        // 도구 비연결 모드 — 순수 텍스트 LLM_CALL.
        return "{\"mcpServers\":{}}";
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
