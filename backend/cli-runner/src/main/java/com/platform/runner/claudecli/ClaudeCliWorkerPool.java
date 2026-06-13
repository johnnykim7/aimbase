package com.platform.runner.claudecli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * CR-050 Phase 2 (PRD-307).
 * 워크플로우 run 단위로 Claude CLI 워커를 관리하는 풀.
 *
 * 수명:
 *   run 시작: 아무 동작 없음 (lazy)
 *   첫 LLM_CALL: {@link #getOrCreateMain} → 메인 워커 spawn
 *   병렬 브랜치: {@link #spawnForkedWorker} → --resume --fork-session 워커 spawn
 *   run 종료: {@link #shutdownForRun} (WorkflowEngine try/finally 에서 호출)
 *
 * 규칙:
 *   - BIZ-100: run당 워커 상한(기본 5) — 초과 시 acquire 대기(큐잉).
 *   - 워커 크래시 감지는 호출 측(Adapter)이 {@link #invalidateMain} 호출로 알림.
 */
public class ClaudeCliWorkerPool {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliWorkerPool.class);

    /** 워커 생성을 추상화 — 테스트에서 stub 스크립트 경로로 오버라이드 가능. */
    @FunctionalInterface
    public interface WorkerFactory {
        ClaudeCliWorker create(String model, String resumeSessionId, boolean forkSession, String configDir);
    }

    private final WorkerFactory workerFactory;
    private final int maxWorkersPerRun;
    private final Duration acquireTimeout;
    /** CR-069: spawn 시 default toolMode (application.yml 의 platform.llm.anthropic-cli.tool-mode). */
    private final ClaudeCliCommandBuilder.ToolMode defaultToolMode;

    private final Map<String, RunWorkers> runs = new ConcurrentHashMap<>();

    public ClaudeCliWorkerPool(WorkerFactory workerFactory, int maxWorkersPerRun,
                               Duration acquireTimeout) {
        this(workerFactory, maxWorkersPerRun, acquireTimeout, null);
    }

    public ClaudeCliWorkerPool(WorkerFactory workerFactory, int maxWorkersPerRun,
                               Duration acquireTimeout,
                               ClaudeCliCommandBuilder.ToolMode defaultToolMode) {
        this.workerFactory = workerFactory;
        this.maxWorkersPerRun = maxWorkersPerRun > 0 ? maxWorkersPerRun : 5;
        this.acquireTimeout = acquireTimeout != null ? acquireTimeout : Duration.ofSeconds(60);
        this.defaultToolMode = defaultToolMode;
    }

    /**
     * 메인 워커를 반환하거나 lazy spawn 한다. 같은 runId 재호출 시 기존 워커 재사용.
     * 워커가 죽어있으면 재기동.
     */
    /** 기존 호출 호환. systemPrompt/toolMode 는 default 로 전달. */
    public ClaudeCliWorker getOrCreateMain(String runId, String model, String configDir) {
        return getOrCreateMain(runId, model, configDir, null, null);
    }

    /** CR-068 호환: systemPrompt override 만 지원 (toolMode default). */
    public ClaudeCliWorker getOrCreateMain(String runId, String model, String configDir,
                                            String systemPrompt) {
        return getOrCreateMain(runId, model, configDir, systemPrompt, null);
    }

    /**
     * CR-068 + CR-069: systemPrompt + toolMode override 지원.
     * 메인 워커가 이미 살아있으면 둘 다 무시(이미 spawn 시 결정됨).
     */
    public ClaudeCliWorker getOrCreateMain(String runId, String model, String configDir,
                                            String systemPrompt,
                                            ClaudeCliCommandBuilder.ToolMode toolMode) {
        return getOrCreateMain(runId, model, configDir, systemPrompt, toolMode, null);
    }

    /**
     * CR-104: systemPrompt + toolMode + allowedTools override 지원.
     * 메인 워커가 이미 살아있으면 셋 다 무시(이미 spawn 시 결정됨 — run 단위 도구 집합 고정).
     * allowedTools 는 원본 도구명 목록 — Worker 가 mcp__aimbase-server__ prefix 변환 후 주입.
     */
    public ClaudeCliWorker getOrCreateMain(String runId, String model, String configDir,
                                            String systemPrompt,
                                            ClaudeCliCommandBuilder.ToolMode toolMode,
                                            List<String> allowedTools) {
        RunWorkers rw = runs.computeIfAbsent(runId, id -> new RunWorkers(maxWorkersPerRun));
        synchronized (rw) {
            if (rw.main != null && rw.main.isAlive()) return rw.main;

            acquireSlot(rw, runId);
            try {
                ClaudeCliWorker worker = workerFactory.create(model, null, false, configDir);
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    worker.setSystemPromptOverride(systemPrompt);
                }
                ClaudeCliCommandBuilder.ToolMode effectiveMode = toolMode != null ? toolMode : defaultToolMode;
                if (effectiveMode != null) {
                    worker.setToolMode(effectiveMode);
                }
                if (allowedTools != null && !allowedTools.isEmpty()) {
                    worker.setAllowedTools(allowedTools);
                }
                worker.start();
                rw.main = worker;
                rw.forks.add(worker); // slot 추적용 (main 도 전체 리스트에 포함)
                log.info("Run '{}': main worker spawned (systemPrompt={}, toolMode={})",
                        runId,
                        systemPrompt != null ? "override(" + systemPrompt.length() + " chars)" : "default",
                        effectiveMode != null ? effectiveMode : "AIMBASE(builder default)");
                return worker;
            } catch (IOException e) {
                rw.slots.release();
                throw new ClaudeCliException("Failed to spawn main worker for run " + runId, e);
            }
        }
    }

    /**
     * 병렬 브랜치 키 기준으로 fork 워커를 반환하거나 새로 spawn.
     * 같은 (runId, branchKey) 재호출 시 기존 워커 재사용 — 브랜치 스코프 내 여러 LLM_CALL 이
     * 같은 CLI 프로세스를 공유하게 한다.
     *
     * @param runId         부모 runId (shutdownForRun 의 스코프)
     * @param branchKey     브랜치 식별자 (예: 스텝 id, 슬롯 번호)
     * @param parentSessionId 메인 워커의 session_id — fork 용
     * @param model         CLI --model (nullable)
     * @param configDir     CLAUDE_CONFIG_DIR (nullable)
     */
    public ClaudeCliWorker getOrSpawnBranchWorker(String runId, String branchKey,
                                                   String parentSessionId,
                                                   String model, String configDir) {
        RunWorkers rw = runs.computeIfAbsent(runId, id -> new RunWorkers(maxWorkersPerRun));
        synchronized (rw) {
            ClaudeCliWorker existing = rw.branchWorkers.get(branchKey);
            if (existing != null && existing.isAlive()) return existing;
            // 죽은 워커가 있으면 슬롯/맵 정리
            if (existing != null) {
                rw.branchWorkers.remove(branchKey);
                rw.forks.remove(existing);
                rw.slots.release();
            }
        }
        ClaudeCliWorker spawned = spawnForkedWorker(runId, parentSessionId, model, configDir);
        synchronized (rw) {
            rw.branchWorkers.put(branchKey, spawned);
        }
        return spawned;
    }

    /**
     * 브랜치 스코프 종료 시 fork 워커를 정리. (runId, branchKey) 로 저장된 워커를 닫고 슬롯 반환.
     */
    public void releaseBranchWorker(String runId, String branchKey) {
        RunWorkers rw = runs.get(runId);
        if (rw == null) return;
        ClaudeCliWorker worker;
        synchronized (rw) {
            worker = rw.branchWorkers.remove(branchKey);
        }
        if (worker != null) releaseForkedWorker(runId, worker);
    }

    /**
     * 병렬 브랜치용 fork 워커 spawn. 메인 워커의 {@code sessionId} 를 기반으로
     * {@code --resume --fork-session} 으로 분기 (prompt prefix 캐시 재사용).
     *
     * @return 새 fork 워커 (호출자는 사용 후 {@link #releaseForkedWorker} 로 반납).
     */
    public ClaudeCliWorker spawnForkedWorker(String runId, String parentSessionId,
                                             String model, String configDir) {
        if (parentSessionId == null || parentSessionId.isBlank()) {
            throw new ClaudeCliException(
                    "Cannot fork — parent session_id not available (main turn not completed)");
        }
        RunWorkers rw = runs.computeIfAbsent(runId, id -> new RunWorkers(maxWorkersPerRun));
        acquireSlot(rw, runId);
        try {
            ClaudeCliWorker worker = workerFactory.create(model, parentSessionId, true, configDir);
            worker.start();
            synchronized (rw) {
                rw.forks.add(worker);
            }
            log.info("Run '{}': fork worker spawned (parent session={})", runId, parentSessionId);
            return worker;
        } catch (IOException e) {
            rw.slots.release();
            throw new ClaudeCliException("Failed to spawn fork worker for run " + runId, e);
        }
    }

    /** 병렬 브랜치 종료 시 fork 워커를 닫고 슬롯 반환. */
    public void releaseForkedWorker(String runId, ClaudeCliWorker worker) {
        if (worker == null) return;
        RunWorkers rw = runs.get(runId);
        if (rw == null) {
            worker.close();
            return;
        }
        synchronized (rw) {
            if (rw.main == worker) {
                log.warn("Run '{}': releaseForkedWorker called on main worker — ignored", runId);
                return;
            }
            if (rw.forks.remove(worker)) {
                rw.slots.release();
            }
        }
        worker.close();
    }

    /**
     * 메인 워커가 크래시 감지되었을 때 호출. 다음 {@link #getOrCreateMain} 에서 재기동된다.
     */
    public void invalidateMain(String runId) {
        RunWorkers rw = runs.get(runId);
        if (rw == null) return;
        synchronized (rw) {
            if (rw.main != null) {
                ClaudeCliWorker dead = rw.main;
                rw.main = null;
                rw.forks.remove(dead);
                rw.slots.release();
                dead.close();
                log.warn("Run '{}': main worker invalidated", runId);
            }
        }
    }

    /**
     * Run 종료 시 모든 워커 정리. 프로세스 누수 방지의 핵심.
     * WorkflowEngine try/finally 에서 반드시 호출.
     */
    public void shutdownForRun(String runId) {
        RunWorkers rw = runs.remove(runId);
        if (rw == null) return;
        List<ClaudeCliWorker> all;
        synchronized (rw) {
            all = new ArrayList<>(rw.forks);
            rw.branchWorkers.clear();
        }
        for (ClaudeCliWorker w : all) {
            try { w.close(); } catch (Exception e) {
                log.warn("Run '{}': worker close error: {}", runId, e.getMessage());
            }
        }
        log.info("Run '{}': shutdown complete, closed {} workers", runId, all.size());
    }

    public int activeWorkerCount(String runId) {
        RunWorkers rw = runs.get(runId);
        if (rw == null) return 0;
        synchronized (rw) {
            return rw.forks.size();
        }
    }

    public int activeRunCount() {
        return runs.size();
    }

    private void acquireSlot(RunWorkers rw, String runId) {
        try {
            if (!rw.slots.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ClaudeCliException(
                        "Run '" + runId + "': worker slot acquire timeout "
                                + "(max=" + maxWorkersPerRun + ", waited=" + acquireTimeout.toSeconds() + "s)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeCliException("Run '" + runId + "': interrupted waiting for worker slot");
        }
    }

    private static final class RunWorkers {
        final Semaphore slots;
        final List<ClaudeCliWorker> forks = new ArrayList<>();
        /** 브랜치 키 → fork 워커. {@link #getOrSpawnBranchWorker} 가 재사용용으로 사용. */
        final java.util.Map<String, ClaudeCliWorker> branchWorkers = new java.util.HashMap<>();
        ClaudeCliWorker main;

        RunWorkers(int max) {
            this.slots = new Semaphore(max, true);
        }
    }
}
