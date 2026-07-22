package com.platform.agent.runner;

import com.platform.runner.claudecli.ClaudeCliAdapterConfig;
import com.platform.runner.claudecli.ClaudeCliWorker;
import com.platform.runner.claudecli.ClaudeCliWorkerPool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * CR-071 Phase 2: Runner 모드 활성 시 Spring Bean 등록.
 *
 * <p>{@code aimbase.runner.enabled=true} (또는 {@code --runner-mode} 진입 시 자동 설정) 일 때만
 * RunnerController + RunnerService + ClaudeCliWorkerPool 이 빈으로 올라온다.
 *
 * <p>그 외 모드(기본 등록 모드, --mcp-stdio) 에서는 본 설정이 비활성 — HTTP 서버도 뜨지 않는다.
 */
@Configuration
@EnableConfigurationProperties(RunnerProperties.class)
@ConditionalOnProperty(prefix = "aimbase.runner", name = "enabled", havingValue = "true")
public class RunnerAutoConfiguration {

    @Bean
    public ClaudeCliAdapterConfig claudeCliAdapterConfig(RunnerProperties props) {
        ClaudeCliAdapterConfig cfg = new ClaudeCliAdapterConfig();
        cfg.setEnabled(props.isEnabled());
        cfg.setCliBinaryPath(props.getClaudeBinary());
        cfg.setMaxWorkersPerRun(props.getMaxWorkers());
        if (props.getAimbaseMcpJar() != null) {
            cfg.setAimbaseAgentJar(props.getAimbaseMcpJar());
        }
        // CR-072: 서버 MCP endpoint 노출 활성 시 mcpServers 다중화
        cfg.setServerMcpBaseUrl(props.getServerMcpBaseUrl());
        cfg.setServerMcpApiKey(props.getServerMcpApiKey());
        cfg.setServerMcpAgentId(props.getServerMcpAgentId());
        return cfg;
    }

    @Bean
    public ClaudeCliWorkerPool claudeCliWorkerPool(RunnerProperties props, ClaudeCliAdapterConfig adapterConfig) {
        // CR-072: 빌드된 mcpConfigJson 을 Worker 에 주입 — AIMBASE/HYBRID 일 때 CommandBuilder 가 그대로 박음.
        String mcpConfig = adapterConfig.resolveMcpConfigJson();
        ClaudeCliWorkerPool.WorkerFactory factory = (model, resumeSessionId, forkSession, configDir) ->
                new ClaudeCliWorker(
                        props.getClaudeBinary(),
                        model,
                        resumeSessionId,
                        forkSession,
                        configDir,
                        Duration.ofSeconds(props.getTurnTimeoutSeconds()),  // CR-106: 설정화 (기본 300s)
                        mcpConfig);
        // CR-121: reaper 설정 주입 — idle 좀비 워커 자동 회수.
        ClaudeCliWorkerPool pool = new ClaudeCliWorkerPool(factory, props.getMaxWorkers(), Duration.ofSeconds(60),
                (com.platform.runner.claudecli.ClaudeCliCommandBuilder.ToolMode) null,
                Duration.ofSeconds(props.getReaperIntervalSeconds()),
                Duration.ofSeconds(props.getReaperIdleThresholdSeconds()));
        // CR-126: AIMBASE 봉인 대상 override (미설정이면 null → 빌더 기본 상수)
        pool.setSealedNativeTools(props.resolveSealedNativeTools());
        return pool;
    }

    @Bean
    public RunnerService runnerService(ClaudeCliWorkerPool pool, RunnerProperties props) {
        return new RunnerService(pool, props.getDefaultModel());
    }

    @Bean
    public RunnerController runnerController(RunnerService service, RunnerProperties props) {
        return new RunnerController(service, props);
    }
}
