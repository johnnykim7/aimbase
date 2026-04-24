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

    public Duration turnTimeout() { return Duration.ofSeconds(timeoutSeconds); }

    @Bean
    public ClaudeCliWorkerPool claudeCliWorkerPool() {
        Duration turnTimeout = turnTimeout();
        ClaudeCliWorkerPool.WorkerFactory factory = (model, resumeSessionId, forkSession, configDir) ->
                new ClaudeCliWorker(cliBinaryPath, model, resumeSessionId, forkSession, configDir, turnTimeout);
        return new ClaudeCliWorkerPool(factory, maxWorkersPerRun,
                Duration.ofSeconds(acquireTimeoutSeconds));
    }
}
