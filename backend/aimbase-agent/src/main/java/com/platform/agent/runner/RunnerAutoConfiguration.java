package com.platform.agent.runner;

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
    public ClaudeCliWorkerPool claudeCliWorkerPool(RunnerProperties props) {
        ClaudeCliWorkerPool.WorkerFactory factory = (model, resumeSessionId, forkSession, configDir) ->
                new ClaudeCliWorker(
                        props.getClaudeBinary(),
                        model,
                        resumeSessionId,
                        forkSession,
                        configDir,
                        Duration.ofSeconds(300),
                        // mcpConfigJson 은 Pool 외부에서 주입 안 됨. AIMBASE/HYBRID 모드는 CommandBuilder 가
                        // --mcp-config 인라인 처리하므로 Worker 의 mcpConfigJson 은 비워둔다.
                        null);
        return new ClaudeCliWorkerPool(factory, props.getMaxWorkers(), Duration.ofSeconds(60));
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
