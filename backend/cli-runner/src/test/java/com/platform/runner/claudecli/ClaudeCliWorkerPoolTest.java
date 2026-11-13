package com.platform.runner.claudecli;

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

    /** CR-175 TC-CHT-020: 답하는 워커가 없으면 멈춤을 보내지 않고 워커도 끄지 않는다. */
    @Test
    void interruptRun_sends_nothing_when_no_worker_is_answering() {
        ClaudeCliWorkerPool pool = newPool(5);
        assertThat(pool.interruptRun("run-none")).isZero();
        ClaudeCliWorker main = pool.getOrCreateMain("run-I", null, null);
        assertThat(pool.interruptRun("run-I")).isZero();
        assertThat(pool.getOrCreateMain("run-I", null, null)).isSameAs(main);

        pool.shutdownForRun("run-I");
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
    void model_change_respawns_main_worker_and_closes_previous() {
        ClaudeCliWorkerPool pool = newPool(5);
        ClaudeCliWorker sonnet = pool.getOrCreateMain("run-model", "claude-sonnet-5-5", null);
        ClaudeCliWorker sameAgain = pool.getOrCreateMain("run-model", "claude-sonnet-5-5", null);
        ClaudeCliWorker opus = pool.getOrCreateMain("run-model", "claude-opus-5-5", null);

        assertThat(sameAgain).isSameAs(sonnet);
        assertThat(opus).isNotSameAs(sonnet);
        assertThat(opus.getModel()).isEqualTo("claude-opus-5-5");
        assertThat(sonnet.isAlive()).isFalse();
        pool.shutdownForRun("run-model");
    }

    @Test
    void cr182_channel_tools_change_respawns_main_worker() {
        // 대화 중에 파일 만들기를 켜거나 데이터 파일을 처음 붙이면 통로 도구가 바뀐다. 기동 인자라 다시 기동해야 한다.
        ClaudeCliWorkerPool pool = newPool(5);
        ClaudeCliWorker dataOnly = pool.getOrCreateMain("run-ch", null, null, null,
                ClaudeCliCommandBuilder.ToolMode.NATIVE, null, null, false, null, "cs-1", List.of("data_query"));
        ClaudeCliWorker sameAgain = pool.getOrCreateMain("run-ch", null, null, null,
                ClaudeCliCommandBuilder.ToolMode.NATIVE, null, null, false, null, "cs-1", null);
        ClaudeCliWorker withFile = pool.getOrCreateMain("run-ch", null, null, null,
                ClaudeCliCommandBuilder.ToolMode.NATIVE, null, null, false, null, "cs-1",
                List.of("data_query", "create_file"));

        assertThat(sameAgain).isSameAs(dataOnly);
        assertThat(withFile).isNotSameAs(dataOnly);
        assertThat(withFile.channelKey()).containsExactly("data_query", "create_file");
        assertThat(dataOnly.isAlive()).isFalse();
        pool.shutdownForRun("run-ch");
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

    // ─── CR-121 ───────────────────────────────────────────────────────────

    @Test
    void shutdownForRunPrefix_closes_all_runs_with_matching_prefix() {
        ClaudeCliWorkerPool pool = newPool(5);
        // LARGE_INPUT 청크처럼 부모 runId 접두사를 공유하는 서로 다른 키들.
        pool.getOrCreateMain("parent-1-li-extract.body[0]-c0", null, null);
        pool.getOrCreateMain("parent-1-li-extract.body[1]-c0", null, null);
        pool.getOrCreateMain("parent-1-li-verify.body[0]-c0", null, null);
        // 다른 부모 — 접두사 불일치라 살아남아야 한다.
        pool.getOrCreateMain("parent-2-li-extract.body[0]-c0", null, null);
        assertThat(pool.activeRunCount()).isEqualTo(4);

        int closed = pool.shutdownForRunPrefix("parent-1");
        assertThat(closed).isEqualTo(3);
        assertThat(pool.activeRunCount()).isEqualTo(1);
        assertThat(pool.activeWorkerCount("parent-2-li-extract.body[0]-c0")).isEqualTo(1);

        pool.shutdownForRun("parent-2-li-extract.body[0]-c0");
    }

    @Test
    void reaper_closes_idle_workers() throws InterruptedException {
        // sweep 0.2s, idle 임계 0.3s — 짧게 잡아 idle 워커가 회수되는지 확인.
        ClaudeCliWorkerPool pool = new ClaudeCliWorkerPool(
                (model, sid, fork, cfg) -> new ClaudeCliWorker(
                        binary.toString(), model, sid, fork, cfg, Duration.ofSeconds(5)),
                5, Duration.ofSeconds(2), null,
                Duration.ofMillis(200), Duration.ofMillis(300));
        ClaudeCliWorker w = pool.getOrCreateMain("idle-run", null, null);
        assertThat(w.isAlive()).isTrue();
        assertThat(pool.activeRunCount()).isEqualTo(1);

        // idle 임계 + sweep 주기 이상 대기 → reaper 가 회수.
        long deadline = System.currentTimeMillis() + 3000;
        while (pool.activeRunCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(pool.activeRunCount()).isEqualTo(0);
        pool.shutdownReaper();
    }

    @Test
    void cr147_failed_main_start_gives_its_slot_back() {
        // 스킬 지정이 있는데 서버 MCP 항목이 없으면 기동 전에 실패한다. 잡은 자리를 돌려줘야 다음 호출이 대기하지 않는다.
        ClaudeCliWorkerPool pool = newPool(1);
        List<com.platform.tool.SkillRef> pins = List.of(new com.platform.tool.SkillRef("ax-proposal", "tenant", 1));

        assertThatThrownBy(() -> pool.getOrCreateMain("run-K", null, null, null,
                ClaudeCliCommandBuilder.ToolMode.NATIVE, null, null, false, pins))
                .hasMessageContaining("BIZ-123");

        ClaudeCliWorker main = pool.getOrCreateMain("run-K", null, null);
        assertThat(main.isAlive()).isTrue();
        pool.shutdownForRun("run-K");
    }

    @Test
    void cr173_process_limit_queues_a_new_run_until_a_slot_frees() throws Exception {
        ClaudeCliWorkerPool pool = newPool(5);
        pool.setProcessLimit(1, Duration.ofSeconds(5));
        ClaudeCliWorker a = pool.getOrCreateMain("run-A", null, null);

        java.util.concurrent.CompletableFuture<ClaudeCliWorker> b =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> pool.getOrCreateMain("run-B", null, null));
        Thread.sleep(300);
        assertThat(b).isNotDone();
        assertThat(pool.processCount()).isEqualTo(1);

        pool.shutdownForRun("run-A");
        ClaudeCliWorker bw = b.get(3, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(bw.isAlive()).isTrue();
        assertThat(a.isAlive()).isFalse();
        assertThat(pool.processCount()).isEqualTo(1);

        pool.shutdownForRun("run-B");
        assertThat(pool.processCount()).isZero();
    }

    @Test
    void cr173_process_limit_wait_times_out_when_every_process_is_in_use() {
        ClaudeCliWorkerPool pool = newPool(5);
        pool.setProcessLimit(1, Duration.ofMillis(500));
        pool.getOrCreateMain("run-A", null, null);

        assertThatThrownBy(() -> pool.getOrCreateMain("run-B", null, null))
                .isInstanceOf(ClaudeCliException.class)
                .hasMessageContaining("process slot wait timeout");
        assertThat(pool.processCount()).isEqualTo(1);
        assertThat(pool.activeWorkerCount("run-B")).isZero();

        pool.shutdownForRun("run-A");
        assertThat(pool.processCount()).isZero();
    }

    @Test
    void cr173_idle_session_worker_is_closed_to_make_room_and_respawns_fresh() throws Exception {
        ClaudeCliWorkerPool pool = newPool(5);
        pool.setProcessLimit(2, Duration.ofSeconds(5));
        ClaudeCliWorker a = pool.getOrCreateMain("run-A", null, null);
        assertThat(a.claimFirstTurn()).isTrue();
        a.unlease();
        Thread.sleep(20);
        ClaudeCliWorker b = pool.getOrCreateMain("run-B", null, null);
        b.unlease();

        ClaudeCliWorker c = pool.getOrCreateMain("run-C", null, null);

        awaitDead(a);
        assertThat(a.isAlive()).isFalse();
        assertThat(b.isAlive()).isTrue();
        assertThat(c.isAlive()).isTrue();
        assertThat(pool.processCount()).isEqualTo(2);

        // 닫힌 세션의 다음 턴은 새 워커가 받아 대화 전체를 다시 넣는다.
        ClaudeCliWorker a2 = pool.getOrCreateMain("run-A", null, null);
        assertThat(a2).isNotSameAs(a);
        assertThat(a2.claimFirstTurn()).isTrue();
        assertThat(b.isAlive()).isFalse();
        assertThat(pool.processCount()).isEqualTo(2);

        pool.shutdownForRun("run-A");
        pool.shutdownForRun("run-B");
        pool.shutdownForRun("run-C");
        assertThat(pool.processCount()).isZero();
    }

    @Test
    void cr173_same_run_reuses_its_worker_without_another_slot() {
        ClaudeCliWorkerPool pool = newPool(5);
        pool.setProcessLimit(1, Duration.ofMillis(500));
        ClaudeCliWorker a = pool.getOrCreateMain("run-A", null, null);
        ClaudeCliWorker again = pool.getOrCreateMain("run-A", null, null);

        assertThat(again).isSameAs(a);
        assertThat(a.isLeased()).isTrue();
        assertThat(pool.processCount()).isEqualTo(1);
        pool.shutdownForRun("run-A");
    }

    @Test
    void cr173_dead_main_gives_its_slots_back_on_respawn() {
        ClaudeCliWorkerPool pool = newPool(1);
        pool.setProcessLimit(1, Duration.ofMillis(500));
        ClaudeCliWorker a = pool.getOrCreateMain("run-A", null, null);
        ProcessHandle.of(a.pid()).ifPresent(ProcessHandle::destroyForcibly);
        awaitDead(a);

        ClaudeCliWorker a2 = pool.getOrCreateMain("run-A", null, null);

        assertThat(a2).isNotSameAs(a);
        assertThat(a2.isAlive()).isTrue();
        assertThat(pool.processCount()).isEqualTo(1);
        pool.shutdownForRun("run-A");
        assertThat(pool.processCount()).isZero();
    }

    @Test
    void cr173_account_folder_change_closes_the_old_worker_within_the_limit() {
        ClaudeCliWorkerPool pool = newPool(1);
        pool.setProcessLimit(1, Duration.ofMillis(500));
        String work01 = binary.getParent().resolve("work01").toString();
        String work02 = binary.getParent().resolve("work02").toString();
        ClaudeCliWorker a = pool.getOrCreateMain("run-A", null, work01);
        a.unlease();

        ClaudeCliWorker b = pool.getOrCreateMain("run-A", null, work02);

        assertThat(b).isNotSameAs(a);
        assertThat(a.isAlive()).isFalse();
        assertThat(b.getConfigDir()).isEqualTo(work02);
        assertThat(pool.processCount()).isEqualTo(1);
        pool.shutdownForRun("run-A");
        assertThat(pool.processCount()).isZero();
    }

    @Test
    void cr173_burst_of_14_one_shot_runs_never_exceeds_the_limit() throws Exception {
        ClaudeCliWorkerPool pool = newPool(5);
        pool.setProcessLimit(2, Duration.ofSeconds(30));
        java.util.Queue<ClaudeCliWorker> spawned = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.atomic.AtomicInteger peakHeld = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger peakAlive = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean sampling = new java.util.concurrent.atomic.AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                peakHeld.accumulateAndGet(pool.processCount(), Math::max);
                int alive = (int) spawned.stream().filter(ClaudeCliWorker::isAlive).count();
                peakAlive.accumulateAndGet(alive, Math::max);
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
            }
        });
        sampler.start();

        java.util.concurrent.ExecutorService requests = java.util.concurrent.Executors.newFixedThreadPool(14);
        List<java.util.concurrent.Future<?>> done = new java.util.ArrayList<>();
        for (int i = 0; i < 14; i++) {
            String runId = "burst-" + i;
            done.add(requests.submit(() -> {
                ClaudeCliWorker w = pool.getOrCreateMain(runId, null, null);
                spawned.add(w);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    w.unlease();
                }
                // api 가 세션 없는 요청에 답한 뒤 보내는 닫기 신호
                pool.shutdownForRun(runId);
                return null;
            }));
        }
        for (java.util.concurrent.Future<?> f : done) f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        requests.shutdown();
        sampling.set(false);
        sampler.join();

        assertThat(spawned).hasSize(14);
        assertThat(peakHeld.get()).isLessThanOrEqualTo(2);
        assertThat(peakAlive.get()).isLessThanOrEqualTo(2);
        assertThat(pool.processCount()).isZero();
        assertThat(pool.activeRunCount()).isZero();
    }

    private ClaudeCliWorkerPool newPool(int max) {
        // reaper 비활성(interval=0) — 일반 테스트가 reaper 간섭 없이 결정적으로 돌도록.
        return new ClaudeCliWorkerPool(
                (model, sid, fork, cfg) -> new ClaudeCliWorker(
                        binary.toString(), model, sid, fork, cfg, Duration.ofSeconds(5)),
                max, Duration.ofSeconds(2), null,
                Duration.ZERO, Duration.ofMinutes(15));
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
