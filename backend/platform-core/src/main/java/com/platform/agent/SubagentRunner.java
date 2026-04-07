package com.platform.agent;

import com.platform.domain.SubagentRunEntity;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookEvent;
import com.platform.hook.HookInput;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.TokenUsage;
import com.platform.llm.model.UnifiedMessage;
import com.platform.orchestrator.ChatRequest;
import com.platform.orchestrator.ChatResponse;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.repository.SubagentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * CR-030 PRD-207: 서브에이전트 생성 및 실행.
 *
 * - 포그라운드: CompletableFuture.get(timeout) 동기 대기
 * - 백그라운드: Virtual Thread 비동기 실행 → DB 기록으로 결과 조회
 * - 훅 연동: SUBAGENT_START / SUBAGENT_STOP 이벤트 발행
 */
@Component
public class SubagentRunner {

    private static final Logger log = LoggerFactory.getLogger(SubagentRunner.class);

    private final OrchestratorEngine orchestratorEngine;
    private final SubagentRunRepository subagentRunRepository;
    private final WorktreeManager worktreeManager;
    private final HookDispatcher hookDispatcher;
    private final SubagentLifecycleManager lifecycleManager;

    public SubagentRunner(OrchestratorEngine orchestratorEngine,
                          SubagentRunRepository subagentRunRepository,
                          WorktreeManager worktreeManager,
                          HookDispatcher hookDispatcher,
                          SubagentLifecycleManager lifecycleManager) {
        this.orchestratorEngine = orchestratorEngine;
        this.subagentRunRepository = subagentRunRepository;
        this.worktreeManager = worktreeManager;
        this.hookDispatcher = hookDispatcher;
        this.lifecycleManager = lifecycleManager;
    }

    /**
     * 서브에이전트 실행 (포그라운드/백그라운드 자동 분기).
     */
    public SubagentResult run(SubagentRequest request) {
        String runId = UUID.randomUUID().toString();
        String childSessionId = "subagent-" + UUID.randomUUID();

        // 1. Worktree 격리 설정
        WorktreeContext worktreeCtx = null;
        if (request.isolation() == SubagentRequest.IsolationMode.WORKTREE) {
            worktreeCtx = worktreeManager.create(runId);
        }

        // 2. 컨텍스트 생성
        SubagentContext context = new SubagentContext(
                runId, request.parentSessionId(), childSessionId,
                request, worktreeCtx);

        // 3. DB 기록 생성
        SubagentRunEntity entity = createRunEntity(request, runId, childSessionId, worktreeCtx);
        subagentRunRepository.save(entity);

        // 4. 수명 주기 등록
        lifecycleManager.register(context);

        // 5. SUBAGENT_START 훅 발행
        dispatchHook(HookEvent.SUBAGENT_START, runId, childSessionId,
                request.parentSessionId(),
                Map.of("description", request.description(),
                        "isolation", request.isolation().name(),
                        "runInBackground", request.runInBackground()));

        // 6. 실행 분기
        if (request.runInBackground()) {
            return runBackground(context, entity);
        } else {
            return runForeground(context, entity);
        }
    }

    /**
     * 특정 서브에이전트 실행 상태 조회.
     */
    public SubagentResult getStatus(String runId) {
        return subagentRunRepository.findById(UUID.fromString(runId))
                .map(this::toResult)
                .orElseThrow(() -> new IllegalArgumentException("Subagent run not found: " + runId));
    }

    /**
     * 부모 세션의 모든 서브에이전트 실행 목록.
     */
    public List<SubagentResult> listByParentSession(String parentSessionId) {
        return subagentRunRepository
                .findByParentSessionIdOrderByStartedAtDesc(parentSessionId)
                .stream()
                .map(this::toResult)
                .toList();
    }

    // ── 포그라운드 실행 (동기 대기) ──

    private SubagentResult runForeground(SubagentContext context, SubagentRunEntity entity) {
        CompletableFuture<SubagentResult> future = CompletableFuture.supplyAsync(
                () -> executeAgent(context), java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());

        try {
            SubagentResult result = future.get(context.getRequest().timeoutMs(), TimeUnit.MILLISECONDS);
            updateEntity(entity, result);
            dispatchStopHook(context, result);
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            SubagentResult result = SubagentResult.timeout(
                    context.getSubagentRunId(), context.getChildSessionId(),
                    context.getRequest().timeoutMs(), entity.getStartedAt());
            updateEntity(entity, result);
            cleanupWorktree(context);
            dispatchStopHook(context, result);
            return result;
        } catch (Exception e) {
            SubagentResult result = SubagentResult.failed(
                    context.getSubagentRunId(), context.getChildSessionId(),
                    e.getMessage(), System.currentTimeMillis() - entity.getStartedAt().toInstant().toEpochMilli(),
                    entity.getStartedAt());
            updateEntity(entity, result);
            cleanupWorktree(context);
            dispatchStopHook(context, result);
            return result;
        }
    }

    // ── 백그라운드 실행 (비동기) ──

    private SubagentResult runBackground(SubagentContext context, SubagentRunEntity entity) {
        Thread.ofVirtual().name("subagent-" + context.getSubagentRunId()).start(() -> {
            try {
                SubagentResult result = executeAgent(context);
                updateEntity(entity, result);
                dispatchStopHook(context, result);
            } catch (Exception e) {
                log.error("Background subagent failed: runId={}", context.getSubagentRunId(), e);
                SubagentResult result = SubagentResult.failed(
                        context.getSubagentRunId(), context.getChildSessionId(),
                        e.getMessage(),
                        System.currentTimeMillis() - entity.getStartedAt().toInstant().toEpochMilli(),
                        entity.getStartedAt());
                updateEntity(entity, result);
                cleanupWorktree(context);
                dispatchStopHook(context, result);
            }
        });

        // 백그라운드이므로 RUNNING 상태로 즉시 반환
        return SubagentResult.running(context.getSubagentRunId(), context.getChildSessionId());
    }

    // ── 에이전트 실질 실행 ──

    private SubagentResult executeAgent(SubagentContext context) {
        SubagentRequest req = context.getRequest();
        OffsetDateTime startedAt = OffsetDateTime.now();
        long startMs = System.currentTimeMillis();

        // OrchestratorEngine에 ChatRequest 위임
        ChatRequest chatRequest = new ChatRequest(
                req.model(),
                context.getChildSessionId(),
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, req.prompt())),
                false, true,
                null, null,
                req.connectionId()
        );

        ChatResponse response = orchestratorEngine.chat(chatRequest);
        long durationMs = System.currentTimeMillis() - startMs;

        // 텍스트 출력 추출
        String output = response.content().stream()
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .reduce("", (a, b) -> a + b);

        // 구조화된 결과 추출
        Map<String, Object> structured = response.content().stream()
                .filter(b -> b instanceof ContentBlock.Structured)
                .findFirst()
                .map(b -> ((ContentBlock.Structured) b).data())
                .orElse(null);

        // Worktree 변경 여부 확인
        String worktreePath = null;
        String branchName = null;
        if (context.isIsolated()) {
            WorktreeContext wCtx = context.getWorktreeContext();
            boolean hasChanges = worktreeManager.hasChanges(wCtx);
            if (hasChanges) {
                worktreePath = wCtx.worktreePath();
                branchName = wCtx.branchName();
            } else {
                worktreeManager.remove(wCtx);
            }
        }

        return SubagentResult.completed(
                context.getSubagentRunId(), context.getChildSessionId(),
                output, structured, response.usage(), durationMs,
                startedAt, worktreePath, branchName);
    }

    // ── 훅 디스패치 ──

    private void dispatchHook(HookEvent event, String runId, String sessionId,
                              String parentSessionId, Map<String, Object> extra) {
        try {
            hookDispatcher.dispatch(event,
                    HookInput.of(event, sessionId, extra,
                            Map.of("subagentRunId", runId,
                                    "parentSessionId", parentSessionId != null ? parentSessionId : "")));
        } catch (Exception e) {
            log.warn("Hook dispatch failed: event={}, runId={}", event, runId, e);
        }
    }

    private void dispatchStopHook(SubagentContext context, SubagentResult result) {
        lifecycleManager.unregister(context.getSubagentRunId());
        dispatchHook(HookEvent.SUBAGENT_STOP,
                context.getSubagentRunId(), context.getChildSessionId(),
                context.getParentSessionId(),
                Map.of("status", result.status().name(),
                        "exitCode", result.exitCode(),
                        "durationMs", result.durationMs()));
    }

    // ── DB 엔티티 관리 ──

    private SubagentRunEntity createRunEntity(SubagentRequest request,
                                              String runId, String childSessionId,
                                              WorktreeContext wCtx) {
        SubagentRunEntity entity = new SubagentRunEntity();
        entity.setId(UUID.fromString(runId));
        entity.setParentSessionId(request.parentSessionId());
        entity.setChildSessionId(childSessionId);
        entity.setDescription(request.description());
        entity.setPrompt(request.prompt());
        entity.setStatus("RUNNING");
        entity.setIsolationMode(request.isolation().name());
        entity.setRunInBackground(request.runInBackground());
        entity.setTimeoutMs(request.timeoutMs());
        entity.setConfig(request.config());
        if (wCtx != null) {
            entity.setWorktreePath(wCtx.worktreePath());
            entity.setBranchName(wCtx.branchName());
            entity.setBaseCommit(wCtx.baseCommit());
        }
        return entity;
    }

    private void updateEntity(SubagentRunEntity entity, SubagentResult result) {
        entity.setStatus(result.status().name());
        entity.setExitCode(result.exitCode());
        entity.setOutput(result.output());
        entity.setStructuredData(result.structuredData());
        entity.setError(result.error());
        entity.setDurationMs(result.durationMs());
        entity.setCompletedAt(result.completedAt());
        if (result.usage() != null) {
            entity.setInputTokens(result.usage().inputTokens());
            entity.setOutputTokens(result.usage().outputTokens());
        }
        entity.setWorktreePath(result.worktreePath());
        entity.setBranchName(result.branchName());
        subagentRunRepository.save(entity);
    }

    private SubagentResult toResult(SubagentRunEntity e) {
        return new SubagentResult(
                e.getId().toString(), e.getChildSessionId(),
                SubagentResult.Status.valueOf(e.getStatus()),
                e.getOutput(), e.getStructuredData(), e.getExitCode(),
                e.getInputTokens() > 0 || e.getOutputTokens() > 0
                        ? new TokenUsage((int) e.getInputTokens(), (int) e.getOutputTokens())
                        : null,
                e.getWorktreePath(), e.getBranchName(),
                e.getDurationMs(), e.getStartedAt(), e.getCompletedAt(),
                e.getError());
    }

    private void cleanupWorktree(SubagentContext context) {
        if (context.isIsolated()) {
            try {
                worktreeManager.remove(context.getWorktreeContext());
            } catch (Exception e) {
                log.warn("Worktree cleanup failed: {}", context.getWorktreeContext().worktreePath(), e);
            }
        }
    }
}
