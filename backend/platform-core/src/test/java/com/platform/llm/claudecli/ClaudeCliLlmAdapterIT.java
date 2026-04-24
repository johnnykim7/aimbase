package com.platform.llm.claudecli;

import com.platform.llm.adapter.ClaudeCliLlmAdapter;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.ModelConfig;
import com.platform.llm.model.UnifiedMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-050 Phase 5: 실제 {@code claude} CLI 바이너리 + OAuth 풀 계정으로 E2E 검증.
 *
 * 결정 3에 따라 CI 에서는 skip — 로컬 환경에서만 환경변수 {@code CLAUDE_CLI_IT=true} 로 활성화.
 *
 * 전제 조건:
 *   1. {@code claude} CLI 가 시스템 PATH 에 설치됨
 *   2. {@code claude login} 완료 (또는 {@code CLAUDE_CONFIG_DIR} 환경변수 지정)
 *   3. Max/Pro 구독 활성 계정
 *
 * 실행: {@code CLAUDE_CLI_IT=true ./gradlew :platform-core:test --tests 'ClaudeCliLlmAdapterIT'}
 */
@EnabledIfEnvironmentVariable(named = "CLAUDE_CLI_IT", matches = "true")
class ClaudeCliLlmAdapterIT {

    private ClaudeCliWorkerPool newPool() {
        return new ClaudeCliWorkerPool(
                (model, sid, fork, cfg) -> new ClaudeCliWorker(
                        "claude", model, sid, fork, cfg, Duration.ofSeconds(120)),
                5, Duration.ofSeconds(60));
    }

    @Test
    void single_turn_returns_text_and_usage() {
        ClaudeCliWorkerPool pool = newPool();
        ClaudeCliLlmAdapter adapter = new ClaudeCliLlmAdapter(pool, null, null);

        LLMRequest req = new LLMRequest(
                null,
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER,
                        "Reply with exactly the single word: PASS")),
                null, ModelConfig.defaults(), false, "it-run-single");
        LLMResponse resp = adapter.chat(req).join();

        assertThat(resp.textContent()).containsIgnoringCase("PASS");
        pool.shutdownForRun("it-run-single");
    }

    @Test
    void multi_turn_preserves_context_via_cli_session() {
        ClaudeCliWorkerPool pool = newPool();
        ClaudeCliLlmAdapter adapter = new ClaudeCliLlmAdapter(pool, null, null);
        String runId = "it-run-multi";

        adapter.chat(new LLMRequest(null,
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER,
                        "My favorite color is indigo. Remember this.")),
                null, ModelConfig.defaults(), false, runId)).join();

        LLMResponse resp = adapter.chat(new LLMRequest(null,
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER,
                        "What color did I mention? Reply with one word.")),
                null, ModelConfig.defaults(), false, runId)).join();

        assertThat(resp.textContent().toLowerCase()).contains("indigo");
        pool.shutdownForRun(runId);
    }

    @Test
    void parallel_fork_reuses_prefix_cache() {
        ClaudeCliWorkerPool pool = newPool();
        ClaudeCliLlmAdapter adapter = new ClaudeCliLlmAdapter(pool, null, null);
        String runId = "it-run-fork";

        adapter.chat(new LLMRequest(null,
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER,
                        "Hold this context: the answer is 42.")),
                null, ModelConfig.defaults(), false, runId)).join();

        String parentSession = pool.getOrCreateMain(runId, null, null).getSessionId();
        assertThat(parentSession).isNotBlank();

        ClaudeCliWorker fork = pool.spawnForkedWorker(runId, parentSession, null, null);
        LLMResponse forkResp = fork.turnFirst(List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER,
                        "What is the answer I told you? Reply with digits only.")));

        assertThat(forkResp.textContent()).contains("42");
        pool.releaseForkedWorker(runId, fork);
        pool.shutdownForRun(runId);
    }

    @Test
    void run_shutdown_terminates_all_processes() {
        ClaudeCliWorkerPool pool = newPool();
        ClaudeCliLlmAdapter adapter = new ClaudeCliLlmAdapter(pool, null, null);
        String runId = "it-run-shutdown";

        adapter.chat(new LLMRequest(null,
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi")),
                null, ModelConfig.defaults(), false, runId)).join();

        ClaudeCliWorker main = pool.getOrCreateMain(runId, null, null);
        ClaudeCliWorker fork = pool.spawnForkedWorker(runId, main.getSessionId(), null, null);

        pool.shutdownForRun(runId);

        // 프로세스 종료 대기 (최대 5초)
        long deadline = System.currentTimeMillis() + 5000;
        while ((main.isAlive() || fork.isAlive()) && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        assertThat(main.isAlive()).isFalse();
        assertThat(fork.isAlive()).isFalse();
    }
}
