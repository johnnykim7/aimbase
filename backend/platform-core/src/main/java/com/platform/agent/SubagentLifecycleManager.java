package com.platform.agent;

import com.platform.domain.SubagentRunEntity;
import com.platform.repository.SubagentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CR-030 PRD-209: 서브에이전트 수명 주기 관리.
 *
 * - 활성 에이전트 추적 (ConcurrentHashMap)
 * - 타임아웃 감지 및 강제 종료
 * - Worktree 자원 정리
 * - 주기적 스캔으로 고아 실행 정리
 */
@Component
public class SubagentLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(SubagentLifecycleManager.class);

    private final SubagentRunRepository subagentRunRepository;
    private final WorktreeManager worktreeManager;

    /** 활성 서브에이전트 컨텍스트 (runId → context) */
    private final Map<String, SubagentContext> activeAgents = new ConcurrentHashMap<>();

    public SubagentLifecycleManager(SubagentRunRepository subagentRunRepository,
                                    WorktreeManager worktreeManager) {
        this.subagentRunRepository = subagentRunRepository;
        this.worktreeManager = worktreeManager;
    }

    /**
     * 활성 에이전트 등록 (SubagentRunner에서 호출).
     */
    public void register(SubagentContext context) {
        activeAgents.put(context.getSubagentRunId(), context);
        log.debug("Subagent registered: runId={}, parent={}",
                context.getSubagentRunId(), context.getParentSessionId());
    }

    /**
     * 에이전트 완료 시 등록 해제.
     */
    public void unregister(String runId) {
        activeAgents.remove(runId);
        log.debug("Subagent unregistered: runId={}", runId);
    }

    /**
     * 특정 에이전트 강제 취소.
     */
    public boolean cancel(String runId) {
        SubagentContext ctx = activeAgents.remove(runId);
        if (ctx == null) {
            log.warn("Cannot cancel: subagent not found in active map: {}", runId);
            return false;
        }

        // DB 상태 업데이트
        subagentRunRepository.findById(java.util.UUID.fromString(runId))
                .ifPresent(entity -> {
                    entity.setStatus("CANCELLED");
                    entity.setExitCode(3);
                    entity.setCompletedAt(OffsetDateTime.now());
                    entity.setError("Cancelled by lifecycle manager");
                    subagentRunRepository.save(entity);
                });

        // Worktree 정리
        cleanupWorktree(ctx);

        log.info("Subagent cancelled: runId={}", runId);
        return true;
    }

    /**
     * 활성 에이전트 목록.
     */
    public Map<String, SubagentContext> getActiveAgents() {
        return Map.copyOf(activeAgents);
    }

    /**
     * 활성 에이전트 수.
     */
    public int getActiveCount() {
        return activeAgents.size();
    }

    /**
     * 주기적 타임아웃 스캔 (30초 간격).
     * RUNNING 상태에서 타임아웃이 지난 실행을 감지하여 TIMEOUT 처리.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void scanTimeouts() {
        List<SubagentRunEntity> runningEntities = subagentRunRepository.findByStatus("RUNNING");
        OffsetDateTime now = OffsetDateTime.now();

        for (SubagentRunEntity entity : runningEntities) {
            long timeoutMs = entity.getTimeoutMs() > 0 ? entity.getTimeoutMs() : 120_000L;
            OffsetDateTime deadline = entity.getStartedAt().plusNanos(timeoutMs * 1_000_000);

            if (now.isAfter(deadline)) {
                log.warn("Subagent timeout detected: runId={}, started={}, timeout={}ms",
                        entity.getId(), entity.getStartedAt(), timeoutMs);

                entity.setStatus("TIMEOUT");
                entity.setExitCode(2);
                entity.setCompletedAt(now);
                entity.setError("Timed out after " + timeoutMs + "ms");
                entity.setDurationMs(timeoutMs);
                subagentRunRepository.save(entity);

                // 활성 맵에서 제거 + worktree 정리
                SubagentContext ctx = activeAgents.remove(entity.getId().toString());
                if (ctx != null) {
                    cleanupWorktree(ctx);
                }
            }
        }
    }

    /**
     * 주기적 고아 worktree 정리 (5분 간격).
     * DB에 기록이 없거나 이미 완료된 실행의 worktree를 제거.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void cleanupOrphanedWorktrees() {
        List<SubagentRunEntity> completed = subagentRunRepository.findByStatus("COMPLETED");
        completed.addAll(subagentRunRepository.findByStatus("FAILED"));
        completed.addAll(subagentRunRepository.findByStatus("TIMEOUT"));
        completed.addAll(subagentRunRepository.findByStatus("CANCELLED"));

        for (SubagentRunEntity entity : completed) {
            if (entity.getWorktreePath() != null
                    && "WORKTREE".equals(entity.getIsolationMode())
                    && java.nio.file.Files.exists(java.nio.file.Path.of(entity.getWorktreePath()))) {

                // 변경 없으면 자동 정리
                WorktreeContext wCtx = new WorktreeContext(
                        entity.getWorktreePath(),
                        entity.getBranchName() != null ? entity.getBranchName() : "unknown",
                        entity.getBaseCommit());

                if (!worktreeManager.hasChanges(wCtx)) {
                    worktreeManager.remove(wCtx);
                    entity.setWorktreePath(null);
                    entity.setBranchName(null);
                    subagentRunRepository.save(entity);
                    log.info("Orphaned worktree cleaned: runId={}", entity.getId());
                }
            }
        }
    }

    private void cleanupWorktree(SubagentContext ctx) {
        if (ctx.isIsolated()) {
            try {
                worktreeManager.remove(ctx.getWorktreeContext());
            } catch (Exception e) {
                log.warn("Worktree cleanup failed: {}", ctx.getWorktreeContext().worktreePath(), e);
            }
        }
    }
}
