package com.platform.llm.claudecli;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * CR-050 Phase 3 (PRD-308).
 * Claude CLI 어댑터 설정 + 풀 Bean 노출.
 *
 * application.yml:
 * <pre>
 * platform:
 *   llm:
 *     anthropic-cli:
 *       enabled: true
 *       timeout-seconds: 300
 *       max-workers-per-run: 5
 *       acquire-timeout-seconds: 60
 *       cli-binary-path: claude   # PATH 검색 — 로컬 환경 기준
 * </pre>
 *
 * 테넌트 피처 플래그는 application.yml 이 아니라 global_config 테이블 키
 * {@code llm.anthropic-cli.enabled-tenants} 에서 읽는다 (결정 4: CR-040 재사용).
 */
@Configuration
@ConfigurationProperties(prefix = "platform.llm.anthropic-cli")
public class ClaudeCliAdapterConfig {

    private boolean enabled = false;
    private int timeoutSeconds = 300;
    private int maxWorkersPerRun = 5;
    private int acquireTimeoutSeconds = 60;
    private String cliBinaryPath = "claude";
    /**
     * Phase 9: Aimbase 도구를 CLI 에 노출하는 MCP 서버 설정 (JSON).
     * 비어있으면 도구 미연결 모드 (순수 텍스트 LLM_CALL 만).
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

    @Bean
    public ClaudeCliWorkerPool claudeCliWorkerPool() {
        Duration turnTimeout = turnTimeout();
        String mcpConfig = resolveMcpConfigJson();
        ClaudeCliWorkerPool.WorkerFactory factory = (model, resumeSessionId, forkSession, configDir) ->
                new ClaudeCliWorker(cliBinaryPath, model, resumeSessionId, forkSession,
                        configDir, turnTimeout, mcpConfig);
        return new ClaudeCliWorkerPool(factory, maxWorkersPerRun,
                Duration.ofSeconds(acquireTimeoutSeconds));
    }
}
