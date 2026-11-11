package com.platform.runner.claudecli;

import com.platform.tool.SkillRef;
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
    /**
     * CR-126: AIMBASE 봉인 대상 built-in 목록 override (aimbase.runner.sealed-native-tools).
     * null/빈 목록이면 {@link ClaudeCliCommandBuilder#DEFAULT_SEALED_NATIVE_TOOLS} 폴백.
     * 생성자 시그니처를 늘리지 않도록 setter 주입 — 미설정이면 현행 기본 동작.
     */
    private volatile List<String> sealedNativeTools;

    private final Map<String, RunWorkers> runs = new ConcurrentHashMap<>();

    /**
     * CR-173: 실행기 전체 Claude 프로세스 상한. maxWorkersPerRun 은 run 하나 안의 상한이라
     * 세션 없는 요청이 몰리면 실행기 전체 수가 끝없이 늘어 컨테이너 메모리 한도를 넘었다.
     * null 이면 상한을 두지 않는다(설정 0).
     */
    private volatile Semaphore processSlots;
    private volatile int maxProcesses;
    private volatile Duration processQueueTimeout = Duration.ZERO;
    /** 실행기 전체 자리를 쥐고 있는 워커. 닫힐 때 한 번만 돌려주려고 둔다. */
    private final java.util.Set<ClaudeCliWorker> processHolders = ConcurrentHashMap.newKeySet();
    /** 줄 선 요청이 쉬는 워커가 생겼는지 다시 보는 간격. 자리가 나면 이 간격을 기다리지 않고 바로 받는다. */
    private static final long IDLE_RECHECK_MS = 1000;

    // CR-121: 좀비 reaper — idle 워커 자동 회수. sweep 주기/idle 임계는 생성자 인자(설정).
    private final Duration reaperInterval;
    private final Duration reaperIdleThreshold;
    private volatile java.util.concurrent.ScheduledExecutorService reaper;

    public ClaudeCliWorkerPool(WorkerFactory workerFactory, int maxWorkersPerRun,
                               Duration acquireTimeout) {
        this(workerFactory, maxWorkersPerRun, acquireTimeout, null);
    }

    public ClaudeCliWorkerPool(WorkerFactory workerFactory, int maxWorkersPerRun,
                               Duration acquireTimeout,
                               ClaudeCliCommandBuilder.ToolMode defaultToolMode) {
        this(workerFactory, maxWorkersPerRun, acquireTimeout, defaultToolMode,
                Duration.ofMinutes(5), Duration.ofMinutes(15));
    }

    /** CR-121: reaper 주기/idle 임계까지 명시하는 풀 생성자. */
    public ClaudeCliWorkerPool(WorkerFactory workerFactory, int maxWorkersPerRun,
                               Duration acquireTimeout,
                               ClaudeCliCommandBuilder.ToolMode defaultToolMode,
                               Duration reaperInterval, Duration reaperIdleThreshold) {
        this.workerFactory = workerFactory;
        this.maxWorkersPerRun = maxWorkersPerRun > 0 ? maxWorkersPerRun : 5;
        this.acquireTimeout = acquireTimeout != null ? acquireTimeout : Duration.ofSeconds(60);
        this.defaultToolMode = defaultToolMode;
        this.reaperInterval = reaperInterval != null ? reaperInterval : Duration.ofMinutes(5);
        this.reaperIdleThreshold = reaperIdleThreshold != null ? reaperIdleThreshold : Duration.ofMinutes(15);
        startReaper();
    }

    /**
     * CR-126: AIMBASE 봉인 대상 built-in 목록 설정 (설정 override).
     * null/빈 목록이면 빌더 기본 상수를 쓴다.
     */
    public void setSealedNativeTools(List<String> toolNames) {
        this.sealedNativeTools = (toolNames == null || toolNames.isEmpty()) ? null : List.copyOf(toolNames);
    }

    /**
     * CR-173: 실행기 전체 Claude 프로세스 상한과 줄 선 요청이 기다리는 상한. 풀을 쓰기 전에 한 번 부른다.
     * 상한에 닿으면 새 요청은 줄을 서고, 기다리는 동안 요청이 쥐고 있지 않은 워커(세션을 이어 쓰려고 남겨 둔 것)가
     * 있으면 가장 오래 쉰 것을 닫아 자리를 낸다. 닫힌 세션은 다음 턴에 새 워커가 대화 전체를 받아 이어 간다.
     */
    public void setProcessLimit(int max, Duration queueTimeout) {
        this.maxProcesses = Math.max(max, 0);
        this.processQueueTimeout = queueTimeout != null && !queueTimeout.isNegative() ? queueTimeout : Duration.ZERO;
        this.processSlots = max > 0 ? new Semaphore(max, true) : null;
        if (max > 0) {
            log.info("CR-173: process limit {} per runner, queue timeout {}", max, this.processQueueTimeout);
        }
    }

    public int processCount() {
        return processHolders.size();
    }

    /** 자리가 비어 있으면 바로 잡는다. 줄 선 요청이 있으면 앞지르지 않는다. 상한이 없으면 늘 true. */
    private boolean tryTakeProcessSlot() {
        Semaphore s = processSlots;
        if (s == null) return true;
        try {
            return s.tryAcquire(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeCliException("interrupted waiting for a process slot");
        }
    }

    /** 자리가 날 때까지 줄을 선다. 기다리는 동안 쉬는 워커를 닫아 자리를 낸다. */
    private void waitForProcessSlot(String runId) {
        Semaphore s = processSlots;
        if (s == null) return;
        long startNs = System.nanoTime();
        long deadlineNs = startNs + processQueueTimeout.toNanos();
        log.info("Run '{}': waiting for a process slot (max={}, held={}, queued={})",
                runId, maxProcesses, processHolders.size(), s.getQueueLength());
        try {
            while (true) {
                closeIdleWorkerForSlot(runId);
                long remainingNs = deadlineNs - System.nanoTime();
                if (remainingNs <= 0) {
                    throw new ClaudeCliException("Run '" + runId + "': process slot wait timeout (max="
                            + maxProcesses + ", waited=" + processQueueTimeout.toSeconds() + "s)");
                }
                long waitNs = Math.min(remainingNs, TimeUnit.MILLISECONDS.toNanos(IDLE_RECHECK_MS));
                if (s.tryAcquire(waitNs, TimeUnit.NANOSECONDS)) {
                    log.info("Run '{}': process slot acquired after {} ms", runId,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs));
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeCliException("Run '" + runId + "': interrupted waiting for a process slot");
        }
    }

    /** 잡은 자리를 막 띄운 워커에 넘긴다. 워커가 닫히면 자리를 돌려받는다. */
    private void handProcessSlotTo(ClaudeCliWorker worker) {
        processHolders.add(worker);
        worker.setCloseListener(() -> releaseProcessSlot(worker));
    }

    private void releaseProcessSlot(ClaudeCliWorker worker) {
        if (processHolders.remove(worker)) returnProcessSlot();
    }

    /** 워커에 넘기지 못한 자리를 돌려준다. */
    private void returnProcessSlot() {
        Semaphore s = processSlots;
        if (s != null) s.release();
    }

    /**
     * 요청이 쥐고 있지 않고 fork 도 없는 메인 워커 가운데 가장 오래 쉰 것을 닫는다.
     * run 항목은 남겨 두어 같은 run 의 다음 턴이 새 워커를 띄우게 한다(빈 항목은 reaper 가 치운다).
     */
    private boolean closeIdleWorkerForSlot(String requesterRunId) {
        String victimRunId = null;
        RunWorkers victimRw = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, RunWorkers> e : runs.entrySet()) {
            RunWorkers rw = e.getValue();
            synchronized (rw) {
                if (!isIdleMainOnly(rw)) continue;
                long last = rw.main.lastActivityMs();
                if (last < oldest) {
                    oldest = last;
                    victimRunId = e.getKey();
                    victimRw = rw;
                }
            }
        }
        if (victimRw == null) return false;
        ClaudeCliWorker victim;
        synchronized (victimRw) {
            if (!isIdleMainOnly(victimRw)) return false;
            victim = victimRw.main;
            victimRw.main = null;
            victimRw.forks.remove(victim);
            victimRw.slots.release();
        }
        victim.close();
        log.info("Run '{}': idle worker closed to free a process slot for run '{}' (pid={})",
                victimRunId, requesterRunId, victim.pid());
        return true;
    }

    private static boolean isIdleMainOnly(RunWorkers rw) {
        ClaudeCliWorker m = rw.main;
        return m != null && !m.isLeased() && rw.forks.size() == 1 && rw.branchWorkers.isEmpty();
    }

    /** CR-121: idle 좀비 워커를 주기적으로 회수하는 데몬 스레드 시작. interval<=0 이면 비활성. */
    private void startReaper() {
        if (reaperInterval.toMillis() <= 0) {
            log.info("CR-121: worker reaper disabled (interval<=0)");
            return;
        }
        this.reaper = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cli-worker-reaper");
            t.setDaemon(true);
            return t;
        });
        long periodMs = reaperInterval.toMillis();
        reaper.scheduleWithFixedDelay(this::reapIdleWorkers, periodMs, periodMs, TimeUnit.MILLISECONDS);
        log.info("CR-121: worker reaper started (interval={}, idleThreshold={})",
                reaperInterval, reaperIdleThreshold);
    }

    /**
     * CR-121: idle 임계를 넘은 워커(또는 이미 죽은 프로세스)를 회수한다. 운영에서 취소/누락 경로의
     * 워커가 어느 cleanup 으로도 안 닫혀 좀비로 누적되던 것을 주기적으로 정리한다(매번 수동 kill 근절).
     */
    void reapIdleWorkers() {
        long now = System.currentTimeMillis();
        long idleMs = reaperIdleThreshold.toMillis();
        int reaped = 0;
        for (Map.Entry<String, RunWorkers> e : runs.entrySet()) {
            RunWorkers rw = e.getValue();
            List<ClaudeCliWorker> snapshot;
            synchronized (rw) {
                snapshot = new ArrayList<>(rw.forks);
            }
            for (ClaudeCliWorker w : snapshot) {
                boolean dead = !w.isAlive();
                boolean idle = (now - w.lastActivityMs()) > idleMs;
                if (dead || idle) {
                    try {
                        w.close();
                    } catch (Exception ce) {
                        log.warn("CR-121: reaper close 실패 run={} pid={}: {}", e.getKey(), w.pid(), ce.getMessage());
                    }
                    synchronized (rw) {
                        rw.forks.remove(w);
                        rw.branchWorkers.values().remove(w);
                        if (rw.main == w) rw.main = null;
                    }
                    reaped++;
                    log.info("CR-121: reaped {} worker run={} pid={} idleMs={}",
                            dead ? "dead" : "idle", e.getKey(), w.pid(), now - w.lastActivityMs());
                }
            }
            synchronized (rw) {
                if (rw.forks.isEmpty()) runs.remove(e.getKey(), rw);
            }
        }
        if (reaped > 0) log.info("CR-121: reaper sweep complete, reaped {} workers", reaped);
    }

    /** CR-121: 풀 종료 시 reaper 정지(테스트/셧다운 누수 방지). */
    public void shutdownReaper() {
        java.util.concurrent.ScheduledExecutorService r = this.reaper;
        if (r != null) r.shutdownNow();
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
        return getOrCreateMain(runId, model, configDir, systemPrompt, toolMode, allowedTools, null);
    }

    /**
     * CR-107 후속: workingDirectory(작업장 절대경로) override 추가.
     * 메인 워커가 이미 살아있으면 무시(run 단위 cwd 고정). Worker 가 CLI 프로세스 cwd 로 설정한다.
     */
    public ClaudeCliWorker getOrCreateMain(String runId, String model, String configDir,
                                            String systemPrompt,
                                            ClaudeCliCommandBuilder.ToolMode toolMode,
                                            List<String> allowedTools,
                                            String workingDirectory) {
        return getOrCreateMain(runId, model, configDir, systemPrompt, toolMode,
                allowedTools, workingDirectory, false);
    }

    /**
     * CR-117: disallowSubagent(CLI 본체 Agent 서브에이전트 차단) override 추가.
     * 메인 워커가 이미 살아있으면 무시(run 단위 도구 집합 고정). true 면 Worker 가
     * {@code --disallowedTools Agent} 를 주입한다. 기본 false = 현행(subagent 허용).
     */
    public ClaudeCliWorker getOrCreateMain(String runId, String model, String configDir,
                                            String systemPrompt,
                                            ClaudeCliCommandBuilder.ToolMode toolMode,
                                            List<String> allowedTools,
                                            String workingDirectory,
                                            boolean disallowSubagent) {
        return getOrCreateMain(runId, model, configDir, systemPrompt, toolMode,
                allowedTools, workingDirectory, disallowSubagent, null);
    }

    /**
     * CR-147: 단계가 고정한 스킬과 판(skillSet) 추가. 메인 워커가 이미 살아있으면 무시한다.
     * AGENT_CALL 은 단계마다 자식 세션(runId)이라 단계별 지정이 한 워커에 섞이지 않는다(BIZ-123).
     */
    public ClaudeCliWorker getOrCreateMain(String runId, String model, String configDir,
                                            String systemPrompt,
                                            ClaudeCliCommandBuilder.ToolMode toolMode,
                                            List<String> allowedTools,
                                            String workingDirectory,
                                            boolean disallowSubagent,
                                            List<SkillRef> skillSet) {
        // CR-173: 돌려주는 워커는 lease 된 상태다. 호출자는 턴을 마치면 unlease 한다(그동안 자리 내기 대상이 아니다).
        // 실행기 전체 자리는 run 잠금 밖에서 기다린다. 잠금을 쥔 채 기다리면 자리 내기가 다른 run 잠금을 잡다 서로 막힌다.
        boolean slotHeld = false;
        try {
            while (true) {
                RunWorkers rw = runs.computeIfAbsent(runId, id -> new RunWorkers(maxWorkersPerRun));
                synchronized (rw) {
                    // reaper 가 빈 항목을 치웠으면 지금 항목으로 다시 한다.
                    if (runs.get(runId) != rw) continue;
                    ClaudeCliWorker live = liveMain(rw, runId, configDir, model);
                    if (live != null) {
                        live.lease();
                        return live;
                    }
                    if (!slotHeld && tryTakeProcessSlot()) slotHeld = true;
                    if (slotHeld) {
                        ClaudeCliWorker worker = spawnMain(rw, runId, model, configDir, systemPrompt, toolMode,
                                allowedTools, workingDirectory, disallowSubagent, skillSet);
                        handProcessSlotTo(worker);
                        slotHeld = false;
                        worker.lease();
                        return worker;
                    }
                }
                waitForProcessSlot(runId);
                slotHeld = true;
            }
        } finally {
            if (slotHeld) returnProcessSlot();
        }
    }

    /**
     * 이어 쓸 수 있는 메인 워커. 프로세스가 죽었거나 계정 폴더나 모델이 바뀌었으면 닫고 null 이다. rw 잠금 안에서 부른다.
     * 죽은 메인을 남겨 두면 run 자리와 실행기 전체 자리를 계속 쥐고 있게 된다.
     */
    private ClaudeCliWorker liveMain(RunWorkers rw, String runId, String configDir, String model) {
        ClaudeCliWorker current = rw.main;
        if (current == null) return null;
        boolean alive = current.isAlive();
        boolean sameAccount = sameConfigDir(current.getConfigDir(), configDir);
        if (alive && sameAccount && sameModel(current.getModel(), model)) return current;
        rw.main = null;
        rw.forks.remove(current);
        rw.slots.release();
        current.close();
        if (alive) {
            // CR-161: 연결 묶음이 같은 run 을 다른 계정 커넥션으로 보냈다. 이전 계정 프로세스를 계속 쓰면
            // api 기록의 커넥션과 실제로 쓴 계정이 어긋나고, 한도에 걸린 계정으로 다시 부른다.
            // CR-175: 위젯에서 모델을 바꿨다. CLI 는 실행 중에 모델을 못 바꾸므로 같은 이유로 새로 띄운다.
            log.info("Run '{}': main worker closed because the {} changed", runId,
                    sameAccount ? "model" : "account folder");
        } else {
            log.info("Run '{}': dead main worker closed before respawn (pid={})", runId, current.pid());
        }
        return null;
    }

    /** rw 잠금 안에서 부른다. */
    private ClaudeCliWorker spawnMain(RunWorkers rw, String runId, String model, String configDir,
                                      String systemPrompt,
                                      ClaudeCliCommandBuilder.ToolMode toolMode,
                                      List<String> allowedTools,
                                      String workingDirectory,
                                      boolean disallowSubagent,
                                      List<SkillRef> skillSet) {
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
            worker.setSealedNativeTools(sealedNativeTools); // CR-126: null 이면 빌더 기본 상수
            if (allowedTools != null && !allowedTools.isEmpty()) {
                worker.setAllowedTools(allowedTools);
            }
            if (workingDirectory != null && !workingDirectory.isBlank()) {
                worker.setWorkingDirectory(workingDirectory);
            }
            if (disallowSubagent) {
                worker.setDisallowSubagent(true); // CR-117: CLI 본체 Agent 서브에이전트 차단
            }
            if (skillSet != null && !skillSet.isEmpty()) {
                worker.setSkillSet(skillSet);
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
        } catch (RuntimeException e) {
            // CR-147: 기동 전 설정 검사 실패(스킬 통로 없음 등)도 잡은 자리를 돌려줘야 다음 호출이 대기하지 않는다.
            rw.slots.release();
            throw e;
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
        ClaudeCliWorker existing;
        synchronized (rw) {
            existing = rw.branchWorkers.get(branchKey);
            if (existing != null && existing.isAlive()) return existing;
            // 죽은 워커가 있으면 슬롯/맵 정리
            if (existing != null) {
                rw.branchWorkers.remove(branchKey);
                rw.forks.remove(existing);
                rw.slots.release();
            }
        }
        // CR-173: 죽은 워커도 닫아야 실행기 전체 자리를 돌려받는다.
        if (existing != null) existing.close();
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
        if (!tryTakeProcessSlot()) waitForProcessSlot(runId);
        boolean slotHeld = true;
        try {
            acquireSlot(rw, runId);
            try {
                ClaudeCliWorker worker = workerFactory.create(model, parentSessionId, true, configDir);
                worker.setSealedNativeTools(sealedNativeTools); // CR-126: main 과 동일 봉인 정책 적용
                worker.start();
                // run 에 올리기 전에 넘겨야, 그 사이 run 종료로 닫혀도 자리가 돌아온다.
                handProcessSlotTo(worker);
                slotHeld = false;
                synchronized (rw) {
                    rw.forks.add(worker);
                }
                log.info("Run '{}': fork worker spawned (parent session={})", runId, parentSessionId);
                return worker;
            } catch (IOException e) {
                rw.slots.release();
                throw new ClaudeCliException("Failed to spawn fork worker for run " + runId, e);
            }
        } finally {
            if (slotHeld) returnProcessSlot();
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
     * CR-175: 그 실행에서 답하고 있는 워커에 멈춤 신호를 보낸다. 워커를 끄지 않아 대화가 이어진다.
     * 사용자의 응답 중지용이다. 대화를 버릴 때는 {@link #shutdownForRun} 을 쓴다.
     *
     * @return 멈춤을 보낸 워커 수
     */
    public int interruptRun(String runId) {
        RunWorkers rw = runs.get(runId);
        if (rw == null) return 0;
        List<ClaudeCliWorker> all;
        synchronized (rw) {
            all = new ArrayList<>(rw.forks);
        }
        int sent = 0;
        for (ClaudeCliWorker w : all) {
            if (w.interrupt()) sent++;
        }
        log.info("Run '{}': interrupt sent to {} of {} workers", runId, sent, all.size());
        return sent;
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

    /**
     * CR-121: {@code prefix} 로 시작하는 모든 run 키의 워커를 일괄 종료한다. LARGE_INPUT 은 청크/재시도마다
     * 키가 {@code {parentRunId}-li-...} 로 달라 {@link #shutdownForRun}(정확 일치)로는 한 번에 못 닫는다.
     * 부모 runId 접두사 하나로 그 run 의 모든 청크 워커를 회수한다.
     *
     * @return 종료한 run(워커 그룹) 수
     */
    public int shutdownForRunPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return 0;
        int groups = 0;
        for (String key : new ArrayList<>(runs.keySet())) {
            if (key.startsWith(prefix)) {
                shutdownForRun(key);
                groups++;
            }
        }
        if (groups > 0) log.info("Prefix '{}': shutdown complete, closed {} run groups", prefix, groups);
        return groups;
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

    /** 비어 있는 계정 폴더는 CLI 기본 폴더 하나로 본다. */
    private static boolean sameModel(String a, String b) {
        String left = a == null || a.isBlank() ? null : a.trim();
        String right = b == null || b.isBlank() ? null : b.trim();
        return java.util.Objects.equals(left, right);
    }

    private static boolean sameConfigDir(String a, String b) {
        String x = a == null || a.isBlank() ? null : a;
        String y = b == null || b.isBlank() ? null : b;
        return java.util.Objects.equals(x, y);
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
