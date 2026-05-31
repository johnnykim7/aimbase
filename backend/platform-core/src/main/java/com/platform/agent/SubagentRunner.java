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
import com.platform.orchestrator.stream.StreamEvent;
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
import java.util.function.Consumer;

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

    /**
     * CR-053 Phase 2: 현재 스트리밍 컨텍스트의 StreamEvent 싱크.
     * ChatController.streamResponse()가 Virtual Thread 시작 시 set, 종료 시 clear.
     * 서브에이전트 실행 중 SubagentStart/Done 이벤트를 이 싱크로 발행.
     * null이면 비스트리밍 경로(REST /subagents 등)이므로 이벤트 발행을 생략.
     */
    private static final ThreadLocal<Consumer<StreamEvent>> STREAM_SINK = new ThreadLocal<>();

    public static void setStreamSink(Consumer<StreamEvent> sink) {
        STREAM_SINK.set(sink);
    }

    public static void clearStreamSink() {
        STREAM_SINK.remove();
    }

    private static void emit(StreamEvent event) {
        Consumer<StreamEvent> sink = STREAM_SINK.get();
        if (sink != null) {
            try {
                sink.accept(event);
            } catch (Exception e) {
                log.warn("Stream sink emit failed: {}", event.getClass().getSimpleName(), e);
            }
        }
    }

    private final OrchestratorEngine orchestratorEngine;
    private final SubagentRunRepository subagentRunRepository;
    private final WorktreeManager worktreeManager;
    private final HookDispatcher hookDispatcher;
    private final SubagentLifecycleManager lifecycleManager;
    private final AgentTypeRegistry agentTypeRegistry;

    public SubagentRunner(OrchestratorEngine orchestratorEngine,
                          SubagentRunRepository subagentRunRepository,
                          WorktreeManager worktreeManager,
                          HookDispatcher hookDispatcher,
                          SubagentLifecycleManager lifecycleManager,
                          AgentTypeRegistry agentTypeRegistry) {
        this.orchestratorEngine = orchestratorEngine;
        this.subagentRunRepository = subagentRunRepository;
        this.worktreeManager = worktreeManager;
        this.hookDispatcher = hookDispatcher;
        this.lifecycleManager = lifecycleManager;
        this.agentTypeRegistry = agentTypeRegistry;
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

        // CR-053 Phase 3: 사용자 가시 텍스트 한 줄을 먼저 주입 — 챗 UI에서 "어떤 에이전트가 뭘 시작했는지" 바로 보임.
        String humanPreamble = buildPreamble(request);
        emit(new StreamEvent.TextDelta(humanPreamble));

        // CR-053 Phase 2: SSE SubagentStart 이벤트 발행 (스트리밍 컨텍스트에서만)
        emit(new StreamEvent.SubagentStart(runId, request.agentType().name(), request.description()));

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
        // VT 는 부모 ThreadLocal 을 상속하지 않으므로 TenantContext / 스트림 싱크를 캡처해 자식 VT 안에서 재주입.
        // 누락 시 OrchestratorEngine → SessionStore → 잘못된 DataSource(=master) → conversation_sessions 없음 오류.
        String tenantId = com.platform.tenant.TenantContext.getTenantId();
        Consumer<StreamEvent> capturedSink = STREAM_SINK.get();
        CompletableFuture<SubagentResult> future = CompletableFuture.supplyAsync(() -> {
            if (tenantId != null) com.platform.tenant.TenantContext.setTenantId(tenantId);
            if (capturedSink != null) STREAM_SINK.set(capturedSink);
            try {
                return executeAgent(context);
            } finally {
                STREAM_SINK.remove();
                com.platform.tenant.TenantContext.clear();
            }
        }, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());

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
        // CR-053 Phase 2: 자식 VT는 부모의 ThreadLocal을 상속하지 않으므로 싱크/TenantContext 를 캡처해서 전달.
        Consumer<StreamEvent> capturedSink = STREAM_SINK.get();
        String tenantId = com.platform.tenant.TenantContext.getTenantId();
        Thread.ofVirtual().name("subagent-" + context.getSubagentRunId()).start(() -> {
            if (capturedSink != null) STREAM_SINK.set(capturedSink);
            if (tenantId != null) com.platform.tenant.TenantContext.setTenantId(tenantId);
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
            } finally {
                STREAM_SINK.remove();
                com.platform.tenant.TenantContext.clear();
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

        // CR-034: 에이전트 타입별 시스템 프롬프트 주입
        AgentTypeRegistry.AgentTypeConfig typeConfig = agentTypeRegistry.getConfig(req.agentType());
        List<UnifiedMessage> messages = new java.util.ArrayList<>();
        if (typeConfig.systemPrompt() != null && !typeConfig.systemPrompt().isBlank()) {
            messages.add(UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM, typeConfig.systemPrompt()));
        }
        messages.add(UnifiedMessage.ofText(UnifiedMessage.Role.USER, req.prompt()));

        // CR-088: config.response_schema → ChatRequest.ResponseFormat 변환.
        // OrchestratorEngine 이 resolveResponseFormat → resolvedSchema 로 풀어 도구 루프에 전달한다
        // (ToolCallHandler.executeLoop CR-088 오버로드). null 이면 기존 동작 그대로.
        ChatRequest.ResponseFormat responseFormat = null;
        Object schemaObj = req.config() != null ? req.config().get("response_schema") : null;
        if (schemaObj instanceof java.util.Map<?, ?> schemaMap) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> schema = (java.util.Map<String, Object>) schemaMap;
            responseFormat = new ChatRequest.ResponseFormat("json_schema", null, schema);
        }

        // OrchestratorEngine에 ChatRequest 위임
        ChatRequest chatRequest = new ChatRequest(
                req.model(),
                context.getChildSessionId(),
                messages,
                false, true,
                null, null,
                req.connectionId(),
                null, null,
                responseFormat
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

        // 응답이 텍스트도 구조화도 비어있으면 — 모델이 'structured_output' 같은 종료 도구로
        // 마무리하지 않고 빈 응답으로 끝낸 케이스. 워크플로우 retry 정책에 태우기 위해 실패로 표시한다.
        // (모델 동작 변동성에 대한 안전망. 정상 흐름은 무영향.)
        if ((output == null || output.isBlank()) && structured == null) {
            log.warn("Subagent run {} produced empty output and no structured data — treating as FAILED",
                    context.getSubagentRunId());
            return SubagentResult.failed(
                    context.getSubagentRunId(), context.getChildSessionId(),
                    "Empty model response (no text, no structured output)",
                    durationMs, startedAt);
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

        // CR-053 Phase 2: SSE SubagentDone 이벤트 발행 (스트리밍 컨텍스트에서만)
        String summary = buildDoneSummary(result);
        emit(new StreamEvent.SubagentDone(
                context.getSubagentRunId(),
                result.status().name(),
                summary,
                result.durationMs()));

        // CR-034: TASK_COMPLETED 훅 (태스크로 실행된 경우)
        if (context.getRequest().runInBackground()) {
            dispatchHook(HookEvent.TASK_COMPLETED,
                    context.getSubagentRunId(), context.getChildSessionId(),
                    context.getParentSessionId(),
                    Map.of("taskId", context.getSubagentRunId(),
                            "status", result.status().name()));
        }
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
        entity.setAgentType(request.agentType().name());
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

    /**
     * CR-053 Phase 3: 서브에이전트 실행 직전에 채팅에 주입할 한 줄 안내.
     * 예: "\n> Explore 서브에이전트로 'T2 파일 분석' 시작합니다.\n\n"
     */
    private String buildPreamble(SubagentRequest request) {
        String typeLabel = switch (request.agentType()) {
            case PLAN -> "Plan";
            case EXPLORE -> "Explore";
            case GUIDE -> "Guide";
            case VERIFICATION -> "Verification";
            case GENERAL -> "General";
        };
        String desc = request.description() != null && !request.description().isBlank()
                ? request.description().strip()
                : "작업";
        return "\n> " + typeLabel + " 서브에이전트로 '" + desc + "' 시작합니다.\n\n";
    }

    /**
     * CR-053 Phase 2: SubagentDone 이벤트의 summary 필드 구성.
     * COMPLETED → output 앞 200자, 실패/타임아웃 → error 메시지.
     */
    private String buildDoneSummary(SubagentResult result) {
        if (result.status() == SubagentResult.Status.COMPLETED && result.output() != null) {
            String out = result.output().strip();
            return out.length() <= 200 ? out : out.substring(0, 200) + "...";
        }
        return result.error() != null ? result.error() : result.status().name();
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
