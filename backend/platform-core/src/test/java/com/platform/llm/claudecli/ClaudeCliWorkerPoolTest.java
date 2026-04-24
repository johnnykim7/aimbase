package com.platform.llm.claudecli;

import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.UnifiedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeCliWorkerPoolTest {

    @TempDir Path tempDir;

    private Path binary;

    @BeforeEach
    void setup() throws IOException {
        binary = writeStub("""
                #!/bin/bash
                # --resume 플래그 유무에 따라 session_id 달리 발행해 fork 구분을 표시
                RESUMED="no"
                for arg in "$@"; do
                  if [ "$arg" = "--resume" ]; then RESUMED="yes"; fi
                done
                if [ "$RESUMED" = "yes" ]; then
                  echo '{"type":"system","subtype":"init","session_id":"sess-fork"}'
                else
                  echo '{"type":"system","subtype":"init","session_id":"sess-main"}'
                fi
                while IFS= read -r line; do
                  if [ -z "$line" ]; then continue; fi
                  echo '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"ok"}]}}'
                  echo '{"type":"result","subtype":"success","result":"ok","session_id":"'"$RESUMED"'","total_cost_usd":0.0,"usage":{"input_tokens":1,"output_tokens":1}}'
                done
                """);
    }

    @Test
    void getOrCreateMain_is_idempotent_per_run() {
        ClaudeCliWorkerPool pool = newPool(5);
        ClaudeCliWorker a = pool.getOrCreateMain("run-1", null, null);
        ClaudeCliWorker b = pool.getOrCreateMain("run-1", null, null);
        assertThat(a).isSameAs(b);

        pool.shutdownForRun("run-1");
    }

    @Test
    void different_runs_get_different_main_workers() {
        ClaudeCliWorkerPool pool = newPool(5);
        ClaudeCliWorker a = pool.getOrCreateMain("run-A", null, null);
        ClaudeCliWorker b = pool.getOrCreateMain("run-B", null, null);
        assertThat(a).isNotSameAs(b);
        assertThat(pool.activeRunCount()).isEqualTo(2);

        pool.shutdownForRun("run-A");
        pool.shutdownForRun("run-B");
        assertThat(pool.activeRunCount()).isZero();
    }

    @Test
    void spawnForkedWorker_requires_parent_session_id() {
        ClaudeCliWorkerPool pool = newPool(5);
        assertThatThrownBy(() -> pool.spawnForkedWorker("run-X", null, null, null))
                .isInstanceOf(ClaudeCliException.class)
                .hasMessageContaining("parent session_id not available");
        pool.shutdownForRun("run-X");
    }

    @Test
    void spawnForkedWorker_creates_independent_fork() {
        ClaudeCliWorkerPool pool = newPool(5);
        ClaudeCliWorker main = pool.getOrCreateMain("run-F", null, null);

        // 메인 워커에서 한 턴 돌려 session_id 확보
        main.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi")));
        assertThat(main.getSessionId()).isEqualTo("sess-main");

        ClaudeCliWorker fork = pool.spawnForkedWorker(
                "run-F", main.getSessionId(), null, null);
        assertThat(fork).isNotSameAs(main);
        assertThat(fork.isAlive()).isTrue();

        // fork 도 한 턴 돌려 확인
        LLMResponse resp = fork.turnFirst(List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, "branch")));
        assertThat(resp.textContent()).isEqualTo("ok");
        assertThat(fork.getSessionId()).isEqualTo("sess-fork");

        pool.releaseForkedWorker("run-F", fork);
        assertThat(fork.isAlive()).isFalse();
        assertThat(main.isAlive()).isTrue();

        pool.shutdownForRun("run-F");
    }

    @Test
    void shutdownForRun_closes_all_workers_and_releases_slots() {
        ClaudeCliWorkerPool pool = newPool(5);
        ClaudeCliWorker main = pool.getOrCreateMain("run-S", null, null);
        main.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi")));
        ClaudeCliWorker f1 = pool.spawnForkedWorker("run-S", main.getSessionId(), null, null);
        ClaudeCliWorker f2 = pool.spawnForkedWorker("run-S", main.getSessionId(), null, null);

        assertThat(pool.activeWorkerCount("run-S")).isEqualTo(3);

        pool.shutdownForRun("run-S");

        // 최대 2초 대기 후 모두 죽어야 한다
        awaitDead(main); awaitDead(f1); awaitDead(f2);
        assertThat(main.isAlive()).isFalse();
        assertThat(f1.isAlive()).isFalse();
        assertThat(f2.isAlive()).isFalse();
        assertThat(pool.activeWorkerCount("run-S")).isZero();
    }

    @Test
    void max_workers_per_run_enforced_with_timeout() {
        // max=2, acquire timeout 200ms — 3번째 spawn은 타임아웃되어야 함
        ClaudeCliWorkerPool pool = new ClaudeCliWorkerPool(
                (model, sid, fork, cfg) -> new ClaudeCliWorker(
                        binary.toString(), model, sid, fork, cfg, Duration.ofSeconds(5)),
                2, Duration.ofMillis(200));

        ClaudeCliWorker main = pool.getOrCreateMain("run-M", null, null);
        main.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi")));
        ClaudeCliWorker f1 = pool.spawnForkedWorker("run-M", main.getSessionId(), null, null);

        assertThatThrownBy(() -> pool.spawnForkedWorker("run-M", main.getSessionId(), null, null))
                .isInstanceOf(ClaudeCliException.class)
                .hasMessageContaining("worker slot acquire timeout");

        pool.releaseForkedWorker("run-M", f1);
        // 이제 슬롯 1개 반환 → 즉시 성공해야 함
        ClaudeCliWorker f2 = pool.spawnForkedWorker("run-M", main.getSessionId(), null, null);
        assertThat(f2.isAlive()).isTrue();

        pool.shutdownForRun("run-M");
    }

    @Test
    void invalidateMain_allows_respawn() {
        ClaudeCliWorkerPool pool = newPool(5);
        ClaudeCliWorker first = pool.getOrCreateMain("run-I", null, null);
        pool.invalidateMain("run-I");
        ClaudeCliWorker second = pool.getOrCreateMain("run-I", null, null);
        assertThat(second).isNotSameAs(first);
        pool.shutdownForRun("run-I");
    }

    @Test
    void concurrent_spawn_respects_max() throws Exception {
        // 동시성 — max=3, 10개 스레드가 spawn 시도. 성공은 최대 3까지만 동시에.
        ClaudeCliWorkerPool pool = new ClaudeCliWorkerPool(
                (model, sid, fork, cfg) -> new ClaudeCliWorker(
                        binary.toString(), model, sid, fork, cfg, Duration.ofSeconds(5)),
                3, Duration.ofSeconds(5));

        ClaudeCliWorker main = pool.getOrCreateMain("run-C", null, null);
        main.turnFirst(List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "hi")));
        String sid = main.getSessionId();

        AtomicInteger live = new AtomicInteger(0);
        AtomicInteger peak = new AtomicInteger(0);
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10; i++) {
                futures.add(CompletableFuture.runAsync(() -> {
                    ClaudeCliWorker w = pool.spawnForkedWorker("run-C", sid, null, null);
                    int n = live.incrementAndGet();
                    peak.accumulateAndGet(n, Math::max);
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    live.decrementAndGet();
                    pool.releaseForkedWorker("run-C", w);
                }, exec));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        }
        // main + fork 슬롯 합산이 max=3 을 넘지 않음. main 이 1 점유 중이므로 fork 동시 최대는 2.
        assertThat(peak.get()).isLessThanOrEqualTo(2);

        pool.shutdownForRun("run-C");
    }

    private ClaudeCliWorkerPool newPool(int max) {
        return new ClaudeCliWorkerPool(
                (model, sid, fork, cfg) -> new ClaudeCliWorker(
                        binary.toString(), model, sid, fork, cfg, Duration.ofSeconds(5)),
                max, Duration.ofSeconds(2));
    }

    private static void awaitDead(ClaudeCliWorker w) {
        long deadline = System.currentTimeMillis() + 2000;
        while (w.isAlive() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
    }

    private Path writeStub(String body) throws IOException {
        Path p = tempDir.resolve("claude-stub-" + System.nanoTime() + ".sh");
        Files.writeString(p, body);
        try {
            Files.setPosixFilePermissions(p, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {}
        return p;
    }
}
