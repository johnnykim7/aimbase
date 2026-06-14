package com.platform.tool;

import com.platform.tool.model.UnifiedToolDef;
import com.platform.domain.ToolExecutionLogEntity;
import com.platform.hook.HookDecision;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookEvent;
import com.platform.hook.HookInput;
import com.platform.hook.HookOutput;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import com.platform.orchestrator.stream.StreamEvent;
import com.platform.policy.PermissionClassifier;
import com.platform.repository.ToolExecutionLogRepository;
import com.platform.tenant.TenantContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM Tool Use 루프를 담당하는 컴포넌트.
 *
 * 흐름:
 * 1. tools를 포함한 LLM 호출
 * 2. finishReason == TOOL_USE 이면:
 *    a. 어시스턴트 tool_use 메시지를 히스토리에 추가
 *    b. 각 ToolCall을 ToolRegistry에서 실행
 *    c. tool_result 메시지를 히스토리에 추가
 *    d. 재호출 (최대 maxIterations회)
 * 3. END 또는 max 도달 시 최종 LLMResponse 반환
 */
@Component
public class ToolCallHandler {

    private static final Logger log = LoggerFactory.getLogger(ToolCallHandler.class);

    private final int maxIterations;
    private final ToolExecutionLogRepository executionLogRepository;
    private final HookDispatcher hookDispatcher;
    private final PermissionClassifier permissionClassifier;
    private final com.platform.tool.compact.ToolResultCompactorRegistry compactorRegistry;
    private final RedisTemplate<String, String> redisTemplate;
    private final com.platform.config.PlatformSettingsService platformSettings;
    /** CR-048 PRD-300: 세션별 활성 도구 필터링 */
    private final com.platform.tool.registry.SessionToolRegistry sessionToolRegistry;

    /** CR-102: 워크플로우 run 컨텍스트(workflowRunId 보유)의 도구 루프 이벤트 적재 */
    private final com.platform.workflow.event.WorkflowRunEventRecorder eventRecorder;
    /** CR-048 PRD-301: 대형 tool result 외부 저장 */
    private final com.platform.tool.storage.ToolResultStorageService toolResultStorage;

    /** CR-047 PRD-298: safeCalls 병렬 실행용 Virtual Thread executor */
    private final ExecutorService parallelToolExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    /** CR-033: Plan Mode 읽기전용 검사에서 허용하는 도구 (Plan Mode 자체 제어용) */
    private static final java.util.Set<String> PLAN_MODE_ALLOWED_WRITES =
            java.util.Set.of("exit_plan_mode", "todo_write");

    /**
     * CR-068: 현재 호출 컨텍스트의 도구 호출 누적 — OrchestratorEngine 이 호출 전 init,
     * executeLoop 가 매 도구 호출 시 append, OrchestratorEngine 이 ChatResponse 에 흘림.
     * ThreadLocal 이라 가상 스레드 propagation 안전(SecurityContextHolder 와 동일 패턴).
     */
    private static final ThreadLocal<List<Map<String, Object>>> CURRENT_ACTIONS = new ThreadLocal<>();

    /** OrchestratorEngine: executeLoop 호출 직전 active. */
    public static void beginActionTracking() {
        CURRENT_ACTIONS.set(new ArrayList<>());
    }

    /** OrchestratorEngine: executeLoop 호출 후 누적 결과 회수 + ThreadLocal 정리. */
    public static List<Map<String, Object>> drainActionTracking() {
        List<Map<String, Object>> actions = CURRENT_ACTIONS.get();
        CURRENT_ACTIONS.remove();
        return actions == null ? List.of() : actions;
    }

    private static void recordAction(ToolCall tc) {
        List<Map<String, Object>> bucket = CURRENT_ACTIONS.get();
        if (bucket != null) {
            bucket.add(Map.of(
                    "name", tc.name(),
                    "input", tc.input() == null ? Map.of() : tc.input()
            ));
        }
    }

    public ToolCallHandler(ToolExecutionLogRepository executionLogRepository,
                           HookDispatcher hookDispatcher,
                           PermissionClassifier permissionClassifier,
                           com.platform.tool.compact.ToolResultCompactorRegistry compactorRegistry,
                           RedisTemplate<String, String> redisTemplate,
                           @org.springframework.beans.factory.annotation.Value("${platform.orchestrator.max-tool-iterations:30}") int maxIterations,
                           com.platform.config.PlatformSettingsService platformSettings,
                           com.platform.tool.registry.SessionToolRegistry sessionToolRegistry,
                           com.platform.tool.storage.ToolResultStorageService toolResultStorage,
                           com.platform.workflow.event.WorkflowRunEventRecorder eventRecorder) {
        this.executionLogRepository = executionLogRepository;
        this.hookDispatcher = hookDispatcher;
        this.permissionClassifier = permissionClassifier;
        this.compactorRegistry = compactorRegistry;
        this.redisTemplate = redisTemplate;
        this.maxIterations = maxIterations;
        this.platformSettings = platformSettings;
        this.sessionToolRegistry = sessionToolRegistry;
        this.toolResultStorage = toolResultStorage;
        this.eventRecorder = eventRecorder;
    }

    /**
     * Tool use 루프를 실행하고 최종 LLMResponse를 반환.
     * toolFilter와 toolChoice를 지원하는 확장 버전.
     *
     * @param adapter       선택된 LLM 어댑터
     * @param resolvedModel 실제 모델 ID (예: "claude-sonnet-4-5")
     * @param messages      현재까지의 메시지 목록 (trimmed)
     * @param config        ModelConfig
     * @param sessionId     세션 ID
     * @param toolRegistry  사용 가능한 도구 레지스트리
     * @param toolFilter    도구 필터링 컨텍스트 (null이면 전체 노출)
     * @param toolChoice    도구 선택 전략 (null이면 auto)
     * @return 최종 LLMResponse (텍스트 응답 포함)
     */
    public LLMResponse executeLoop(
            LLMAdapter adapter,
            String resolvedModel,
            List<UnifiedMessage> messages,
            ModelConfig config,
            String sessionId,
            ToolRegistry toolRegistry,
            ToolFilterContext toolFilter,
            String toolChoice) {

        List<UnifiedMessage> mutableMessages = new ArrayList<>(messages);
        LLMResponse response = null;

        // 필터링된 도구 목록 (루프 전체에서 동일하게 사용)
        List<UnifiedToolDef> filteredTools =
                sessionToolRegistry.filterActive(sessionId, toolRegistry.getToolDefs(toolFilter));

        if (filteredTools.isEmpty()) {
            log.debug("No tools available after filtering, executing without tools");
            LLMRequest request = new LLMRequest(
                    resolvedModel, mutableMessages, null,
                    config, false, sessionId, null);
            try {
                return adapter.chat(request).get();
            } catch (Exception e) {
                throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
            }
        }

        // CR-049 BIZ-097: Stop Hook BLOCK 재진입 시 동일 사유 누적 카운터 (3회 초과 시 강제 종료)
        java.util.Map<String, Integer> stopBlockReasonCount = new java.util.HashMap<>();

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            LLMRequest request = new LLMRequest(
                    resolvedModel,
                    mutableMessages,
                    filteredTools,
                    config,
                    false,
                    sessionId,
                    toolChoice
            );

            try {
                response = adapter.chat(request).get();
            } catch (Exception e) {
                log.error("LLM call failed during tool loop (iteration {})", iteration, e);
                throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
            }

            log.debug("Tool loop iteration {}: finishReason={}, toolCalls={}",
                    iteration, response.finishReason(),
                    response.toolCalls() != null ? response.toolCalls().size() : 0);

            if (response.finishReason() != LLMResponse.FinishReason.TOOL_USE
                    || !response.hasToolCalls()) {
                // CR-049 PRD-304: 턴 종료 직전 STOP 훅으로 사용자 정의 검증 hook(TodoWrite 미완료/테스트 FAIL 등)을 실행.
                // BLOCK 이면 사유를 새 user 메시지로 주입하고 재진입 (BIZ-097: 동일 사유 3회 초과 시 강제 종료).
                Map<String, Object> stopInput = Map.of(
                        "iteration", iteration,
                        "finishReason", String.valueOf(response.finishReason()));
                HookOutput stopHook = hookDispatcher.dispatch(
                        HookEvent.STOP,
                        HookInput.of(HookEvent.STOP, sessionId, stopInput, Map.<String, Object>of()),
                        null);
                if (stopHook != null && stopHook.decision() == HookDecision.BLOCK) {
                    Object reasonMeta = stopHook.metadata() != null ? stopHook.metadata().get("reason") : null;
                    String reason = reasonMeta != null ? reasonMeta.toString() : "검증 실패로 재시도가 필요합니다.";
                    int prior = stopBlockReasonCount.getOrDefault(reason, 0);
                    int count = prior + 1;
                    stopBlockReasonCount.put(reason, count);
                    if (count > 3) {
                        log.warn("Stop hook BLOCK 동일 사유 3회 초과, 강제 종료: session={}, reason={}",
                                sessionId, reason);
                        mutableMessages.add(UnifiedMessage.ofText(
                                UnifiedMessage.Role.SYSTEM,
                                "[시스템] 동일한 검증 실패(" + reason + ")가 3회 초과되어 턴을 강제 종료합니다."));
                        break;
                    }
                    log.info("Stop hook BLOCK, 루프 재진입: session={}, count={}/{}, reason={}",
                            sessionId, count, 3, reason);
                    // 새 user 메시지 주입 → 다음 iteration 에서 모델이 reason 을 읽고 보완 작업 수행
                    mutableMessages.add(UnifiedMessage.ofText(
                            UnifiedMessage.Role.USER,
                            "다음 검증 실패를 확인하고 보완해주세요: " + reason));
                    continue;
                }
                break;
            }

            // 1. 어시스턴트 tool_use 메시지 기록
            List<ContentBlock.ToolUse> toolUseBlocks = response.toolCalls().stream()
                    .map(tc -> new ContentBlock.ToolUse(tc.id(), tc.name(), tc.input()))
                    .toList();
            mutableMessages.add(UnifiedMessage.ofAssistantWithToolUse(toolUseBlocks));

            // 2. 각 도구 실행 → tool_result 메시지 기록 (CR-030: 훅 삽입)
            List<ContentBlock.ToolResult> results = new ArrayList<>();
            for (ToolCall tc : response.toolCalls()) {
                log.debug("Executing tool: {} (id={})", tc.name(), tc.id());

                // PRD-193: PreToolUse 훅
                HookOutput preHook = hookDispatcher.dispatch(
                        HookEvent.PRE_TOOL_USE,
                        HookInput.of(HookEvent.PRE_TOOL_USE, sessionId, tc.name(), tc.input()),
                        tc.name());
                if (preHook.decision() == HookDecision.BLOCK) {
                    log.info("Tool execution blocked by hook: tool={}", tc.name());
                    results.add(new ContentBlock.ToolResult(tc.id(),
                            "Tool execution blocked by policy hook"));
                    continue;
                }

                try {
                    String result = toolRegistry.execute(tc);
                    results.add(new ContentBlock.ToolResult(tc.id(), result));

                    // PRD-193: PostToolUse 훅
                    hookDispatcher.dispatch(
                            HookEvent.POST_TOOL_USE,
                            HookInput.of(HookEvent.POST_TOOL_USE, sessionId, tc.name(),
                                    Map.of("result", result != null ? result : "")),
                            tc.name());
                } catch (Exception e) {
                    // PRD-193: PostToolUseFailure 훅
                    hookDispatcher.dispatch(
                            HookEvent.POST_TOOL_USE_FAILURE,
                            HookInput.of(HookEvent.POST_TOOL_USE_FAILURE, sessionId, tc.name(),
                                    Map.of("error", e.getMessage() != null ? e.getMessage() : "unknown")),
                            tc.name());
                    results.add(new ContentBlock.ToolResult(tc.id(), "Error: " + e.getMessage()));
                }
            }
            mutableMessages.add(UnifiedMessage.ofToolResults(results));
        }

        return ensureTextResponse(response, adapter, resolvedModel, mutableMessages, config, sessionId);
    }

    /**
     * CR-029: ToolContext 기반 실행 루프 (기존 호환용 — schema 없음).
     */
    public LLMResponse executeLoop(
            LLMAdapter adapter,
            String resolvedModel,
            List<UnifiedMessage> messages,
            ModelConfig config,
            String sessionId,
            ToolRegistry toolRegistry,
            ToolFilterContext toolFilter,
            String toolChoice,
            ToolContext toolContext) {
        return executeLoop(adapter, resolvedModel, messages, config, sessionId,
                toolRegistry, toolFilter, toolChoice, toolContext, null);
    }

    /**
     * CR-088: ToolContext 기반 실행 루프 + responseSchema 전달.
     *
     * <p>responseSchema 가 있으면 매 회차 LLMRequest 에 함께 실어 어댑터에 전달한다.
     * 어댑터는 진짜 도구 + structured_output 가상 tool 을 한 배열에 합쳐 전달한다
     * (AnthropicAdapter CR-088 결합 분기). 모델이 structured_output tool 을 호출하면
     * 그 입력을 구조화 결과로 캡처하고 루프를 즉시 종료한다.
     */
    public LLMResponse executeLoop(
            LLMAdapter adapter,
            String resolvedModel,
            List<UnifiedMessage> messages,
            ModelConfig config,
            String sessionId,
            ToolRegistry toolRegistry,
            ToolFilterContext toolFilter,
            String toolChoice,
            ToolContext toolContext,
            Map<String, Object> responseSchema) {

        // CR-108: CLI 어댑터는 도구 루프를 자기 프로세스 안에서 완결한다 (CR-050 Phase 9).
        // 외부 도구 루프(아래 for) 를 돌 필요가 없고, 대신 chatStream 으로 호출해
        // 내부 도구 관찰(observedTool chunk)을 turn 도중에 받아 즉시 DB 적재한다 —
        // AGENT_CALL 진행 중에도 도구 흐름이 실시간으로 차오르게 (turn 끝 batch 와 차이).
        if (com.platform.llm.adapter.ClaudeCliAdapter.PROVIDER.equals(adapter.getProvider())) {
            return executeCliStreaming(adapter, resolvedModel, messages, config,
                    sessionId, toolRegistry, toolFilter, toolContext, responseSchema);
        }

        List<UnifiedMessage> mutableMessages = new ArrayList<>(messages);
        LLMResponse response = null;
        // CR-068 (2026-04-26): CR-048 의 sessionToolRegistry.filterActive 가 도구 schema 를 좁혀서
        // 모델이 필요한 도구를 못 받고 환각 답변하는 회귀를 만들었다. 작년 4월 정상 동작 동등으로 환원 —
        // 모든 도구 schema 를 모델에게 전달하고 모델이 자율 선택한다.
        List<UnifiedToolDef> filteredTools = toolRegistry.getToolDefs(toolFilter);

        if (filteredTools.isEmpty()) {
            log.debug("No tools available after filtering, executing without tools");
            LLMRequest request = new LLMRequest(
                    resolvedModel, mutableMessages, null, config, false, sessionId, null, responseSchema);
            try {
                LLMResponse direct = adapter.chat(request).get();
                // CR-102: 도구 없는 단발 호출도 워크플로우 run 컨텍스트면 이벤트 적재
                recordLoopLlmResponse(toolContext, 0, direct, resolvedModel);
                return direct;
            } catch (Exception e) {
                throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
            }
        }

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            LLMRequest request = new LLMRequest(
                    resolvedModel, mutableMessages, filteredTools,
                    config, false, sessionId, toolChoice, responseSchema);

            try {
                response = adapter.chat(request).get();
            } catch (Exception e) {
                log.error("LLM call failed during tool loop (iteration {})", iteration, e);
                throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
            }

            // CR-102: 루프 회차별 LLM_RESPONSE 이벤트 — 응답 본문만 적재
            // (회차 prompt 는 이전 대화 전체의 누적 중복이라 미적재. 도구 결과는 TOOL_RESULT 이벤트로 별도 적재됨)
            recordLoopLlmResponse(toolContext, iteration, response, resolvedModel);

            // CR-088: structured_output tool 호출이 도착했으면 그 입력을 구조화 결과로 캡처하고 종료.
            // (어댑터가 진짜 도구 + structured_output 가상 tool 결합으로 전달한 경우)
            if (responseSchema != null && response.hasToolCalls()) {
                for (ToolCall tc : response.toolCalls()) {
                    if (com.platform.llm.adapter.AnthropicAdapter.STRUCTURED_OUTPUT_TOOL_NAME.equals(tc.name())) {
                        log.debug("CR-088: structured_output tool detected, capturing schema result and terminating loop");
                        java.util.List<ContentBlock> blocks = new ArrayList<>(response.content());
                        blocks.add(new ContentBlock.Structured(null, tc.input()));
                        return new LLMResponse(
                                response.id(), response.model(), blocks,
                                java.util.List.of(),
                                response.usage(), LLMResponse.FinishReason.END,
                                response.latencyMs(), response.costUsd());
                    }
                }
            }

            if (response.finishReason() != LLMResponse.FinishReason.TOOL_USE
                    || !response.hasToolCalls()) {
                break;
            }

            // 어시스턴트 tool_use 메시지
            List<ContentBlock.ToolUse> toolUseBlocks = response.toolCalls().stream()
                    .map(tc -> new ContentBlock.ToolUse(tc.id(), tc.name(), tc.input()))
                    .toList();
            mutableMessages.add(UnifiedMessage.ofAssistantWithToolUse(toolUseBlocks));

            // PRD-196: AUTO 모드 해소 — LLM이 선택한 도구 목록 기반으로 구체 권한 결정
            final ToolContext effectiveContext;
            if (toolContext != null && toolContext.permissionLevel() == PermissionLevel.AUTO) {
                List<String> callNames = response.toolCalls().stream().map(ToolCall::name).toList();
                PermissionLevel resolved = permissionClassifier.classify(callNames, toolRegistry);
                effectiveContext = new ToolContext(
                        toolContext.tenantId(), toolContext.appId(), toolContext.projectId(),
                        toolContext.sessionId(), toolContext.workflowRunId(), toolContext.stepId(),
                        toolContext.subagentRunId(),
                        toolContext.actorUserId(), resolved, toolContext.approvalState(),
                        toolContext.workspacePath(), toolContext.dryRun(), toolContext.turnNumber());
                log.debug("AUTO permission resolved: tools={} → {}", callNames, resolved);
            } else {
                effectiveContext = toolContext;
            }

            // CR-029: concurrencySafe 분기 — OpenClaude partition 패턴
            // concurrencySafe=true인 도구끼리는 병렬, 아니면 순차
            final int turnNum = iteration;
            AtomicInteger seq = new AtomicInteger(0);
            List<ToolCall> toolCalls = response.toolCalls();

            // CR-068: 누적 — OrchestratorEngine 이 ChatResponse.actions_executed 로 흘림
            for (ToolCall tc : toolCalls) recordAction(tc);

            // 병렬 안전한 도구와 아닌 도구 분리
            List<ToolCall> safeCalls = new ArrayList<>();
            List<ToolCall> unsafeCalls = new ArrayList<>();
            for (ToolCall tc : toolCalls) {
                ToolContractMeta meta = toolRegistry.getContractMeta(tc.name());
                if (meta != null && meta.concurrencySafe()) {
                    safeCalls.add(tc);
                } else {
                    unsafeCalls.add(tc);
                }
            }

            // CR-047 PRD-298: safeCalls 병렬 실행 (Virtual Threads + Semaphore 상한)
            // unsafeCalls는 순차 유지. 결과는 원래 tool_use 순서대로 재정렬.
            // CR-095: injectedMessages 에 도구가 주입한 멀티모달(PDF/이미지) user 메시지 수집.
            List<UnifiedMessage> injectedMessages = new ArrayList<>();
            List<ContentBlock.ToolResult> results =
                    runToolsPartitioned(safeCalls, unsafeCalls, effectiveContext, toolRegistry, turnNum, seq, null, injectedMessages);

            // A2 + CR-031 PRD-215 + CR-040: per-message budget + 도구별 지능형 축약 (런타임 설정)
            int compactionThreshold = platformSettings.getInt("orchestrator.tool-result-compaction-threshold", 81920);
            int budgetBytes = platformSettings.getInt("orchestrator.tool-result-budget-bytes", 51200);
            int totalChars = results.stream().mapToInt(r -> r.content().length()).sum();
            if (totalChars > compactionThreshold) {
                log.debug("Tool results total {}B exceeds {}B threshold, applying smart compaction", totalChars, compactionThreshold);
                results.sort((a, b) -> b.content().length() - a.content().length());
                List<ContentBlock.ToolResult> budgeted = new ArrayList<>();
                int remaining = budgetBytes;
                for (ContentBlock.ToolResult r : results) {
                    if (r.content().length() <= remaining) {
                        budgeted.add(r);
                        remaining -= r.content().length();
                    } else {
                        // CR-031: 도구별 지능형 축약 적용
                        String compacted = compactorRegistry.compact(
                                r.toolUseId(), r.content(), Math.min(2000, remaining));
                        // CR-048 PRD-301: 원본을 외부 저장소에 보관하고 체인에는 ref stub 주입
                        String stub = compacted;
                        try {
                            String resultId = toolResultStorage.store(
                                    sessionId, r.toolUseId(), r.content(), compacted);
                            stub = "[tool_result_ref id=" + resultId
                                    + " size=" + r.content().length() + "B]\n" + compacted
                                    + "\n(원본 복구: read_tool_result(result_id=\"" + resultId + "\"))";
                        } catch (Exception ex) {
                            log.warn("Failed to store tool result to tool_result_storage: {}", ex.getMessage());
                        }
                        budgeted.add(new ContentBlock.ToolResult(r.toolUseId(), stub));
                        remaining -= stub.length();
                    }
                }
                results = budgeted;
            }

            mutableMessages.add(UnifiedMessage.ofToolResults(results));
            // CR-095: tool_result 직후 멀티모달 user 메시지 주입 (openclaude newMessages 패턴)
            if (!injectedMessages.isEmpty()) {
                mutableMessages.addAll(injectedMessages);
            }
        }

        return ensureTextResponse(response, adapter, resolvedModel, mutableMessages, config, sessionId);
    }

    /**
     * CR-045 Phase 2-B: 스트리밍 버전의 도구 루프.
     *
     * 기존 executeLoop(ToolContext)와 동일 로직이되, adapter.chat() 대신 adapter.chatStream()을
     * 사용하여 매 청크를 streamSink로 흘리고, 도구 실행 전후에 ToolUseStart/ToolResultEvent를 발행.
     * 완료 시 마지막 LLM 응답의 텍스트를 누적해 최종 LLMResponse를 재구성한다.
     */
    public LLMResponse executeLoopStream(
            LLMAdapter adapter,
            String resolvedModel,
            List<UnifiedMessage> messages,
            ModelConfig config,
            String sessionId,
            ToolRegistry toolRegistry,
            ToolFilterContext toolFilter,
            String toolChoice,
            ToolContext toolContext,
            java.util.function.Consumer<StreamEvent> streamSink) {
        return executeLoopStream(adapter, resolvedModel, messages, config, sessionId,
                toolRegistry, toolFilter, toolChoice, toolContext, streamSink,
                new java.util.concurrent.atomic.AtomicBoolean(false));
    }

    /**
     * CR-046: 중지(abort) 토큰을 받는 스트리밍 도구 루프.
     * 매 iteration 시작 시 토큰을 체크하고, 도구 호출 사이에도 체크하여 즉시 break 한다.
     */
    public LLMResponse executeLoopStream(
            LLMAdapter adapter,
            String resolvedModel,
            List<UnifiedMessage> messages,
            ModelConfig config,
            String sessionId,
            ToolRegistry toolRegistry,
            ToolFilterContext toolFilter,
            String toolChoice,
            ToolContext toolContext,
            java.util.function.Consumer<StreamEvent> streamSink,
            java.util.concurrent.atomic.AtomicBoolean cancelled) {

        List<UnifiedMessage> mutableMessages = new ArrayList<>(messages);
        LLMResponse response = null;
        List<UnifiedToolDef> filteredTools =
                sessionToolRegistry.filterActive(sessionId, toolRegistry.getToolDefs(toolFilter));

        // 도구 없음 → 단순 스트리밍 한 번
        if (filteredTools.isEmpty()) {
            LLMRequest request = new LLMRequest(
                    resolvedModel, mutableMessages, null, config, true, sessionId, null);
            StreamingAccumulator acc = new StreamingAccumulator(streamSink, cancelled);
            adapter.chatStream(request, acc::accept);
            acc.await();
            String text = cancelled.get() ? "[중단됨] " + acc.text() : acc.text();
            streamSink.accept(new StreamEvent.Done(acc.usage()));
            return new LLMResponse(acc.id(), resolvedModel,
                    List.of(new ContentBlock.Text(text)),
                    List.of(), acc.usage() != null ? acc.usage() : new TokenUsage(0, 0),
                    LLMResponse.FinishReason.END, 0, 0);
        }

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            // CR-046: 중지 체크포인트
            if (cancelled.get()) break;

            LLMRequest request = new LLMRequest(
                    resolvedModel, mutableMessages, filteredTools,
                    config, true, sessionId, toolChoice);

            StreamingAccumulator acc = new StreamingAccumulator(streamSink, cancelled);
            adapter.chatStream(request, acc::accept);
            acc.await();

            List<ToolCall> toolCalls = acc.toolUses() != null ? acc.toolUses() : List.of();
            LLMResponse.FinishReason fr = acc.finishReason() != null
                    ? acc.finishReason() : LLMResponse.FinishReason.END;
            TokenUsage usage = acc.usage() != null ? acc.usage() : new TokenUsage(0, 0);
            List<ContentBlock> respContent = acc.text().isEmpty()
                    ? List.of()
                    : List.of(new ContentBlock.Text(acc.text()));
            response = new LLMResponse(acc.id(), resolvedModel, respContent,
                    toolCalls, usage, fr, 0, 0);

            if (fr != LLMResponse.FinishReason.TOOL_USE || toolCalls.isEmpty()) {
                break;
            }

            // 1. 어시스턴트 tool_use 메시지
            List<ContentBlock.ToolUse> toolUseBlocks = toolCalls.stream()
                    .map(tc -> new ContentBlock.ToolUse(tc.id(), tc.name(), tc.input()))
                    .toList();
            mutableMessages.add(UnifiedMessage.ofAssistantWithToolUse(toolUseBlocks));

            // 2. AUTO 권한 해소 (비스트리밍 경로와 동일)
            final ToolContext effectiveContext;
            if (toolContext != null && toolContext.permissionLevel() == PermissionLevel.AUTO) {
                List<String> callNames = toolCalls.stream().map(ToolCall::name).toList();
                PermissionLevel resolved = permissionClassifier.classify(callNames, toolRegistry);
                effectiveContext = new ToolContext(
                        toolContext.tenantId(), toolContext.appId(), toolContext.projectId(),
                        toolContext.sessionId(), toolContext.workflowRunId(), toolContext.stepId(),
                        toolContext.subagentRunId(),
                        toolContext.actorUserId(), resolved, toolContext.approvalState(),
                        toolContext.workspacePath(), toolContext.dryRun(), toolContext.turnNumber());
            } else {
                effectiveContext = toolContext;
            }

            // 3. 도구 실행 + 이벤트 발행
            final int turnNum = iteration;
            AtomicInteger seq = new AtomicInteger(0);

            List<ToolCall> safeCalls = new ArrayList<>();
            List<ToolCall> unsafeCalls = new ArrayList<>();
            for (ToolCall tc : toolCalls) {
                ToolContractMeta meta = toolRegistry.getContractMeta(tc.name());
                if (meta != null && meta.concurrencySafe()) safeCalls.add(tc);
                else unsafeCalls.add(tc);
            }

            // CR-047 PRD-298: safeCalls 병렬 실행 + 스트림 이벤트는 순서대로 발행
            // streamSink는 thread-unsafe 가정 — 병렬 실행은 백그라운드, sink 호출은 메인 스레드에서만.
            // CR-095: injectedMessages 에 도구가 주입한 멀티모달(PDF/이미지) user 메시지 수집.
            List<UnifiedMessage> injectedMessages = new ArrayList<>();
            List<ContentBlock.ToolResult> results = runToolsPartitioned(
                    safeCalls, unsafeCalls, effectiveContext, toolRegistry, turnNum, seq, cancelled, injectedMessages);

            // 결과를 streamSink로 순서대로 발행 (toolCalls 원래 순서)
            java.util.Map<String, ContentBlock.ToolResult> resultById = new java.util.HashMap<>();
            for (ContentBlock.ToolResult r : results) resultById.put(r.toolUseId(), r);
            for (ToolCall tc : toolCalls) {
                if (cancelled.get()) break;
                streamSink.accept(new StreamEvent.ToolUseStart(tc.id(), tc.name(), tc.input()));
                ContentBlock.ToolResult r = resultById.get(tc.id());
                if (r != null) {
                    streamSink.accept(new StreamEvent.ToolResultEvent(r.toolUseId(), r.content(), false));
                }
            }
            if (cancelled.get()) break;  // CR-046: 도구 결과 후 다음 LLM 호출 전 중지

            // 4. 축약 (비스트리밍 경로와 동일)
            int compactionThreshold = platformSettings.getInt("orchestrator.tool-result-compaction-threshold", 81920);
            int budgetBytes = platformSettings.getInt("orchestrator.tool-result-budget-bytes", 51200);
            int totalChars = results.stream().mapToInt(r -> r.content().length()).sum();
            if (totalChars > compactionThreshold) {
                results.sort((a, b) -> b.content().length() - a.content().length());
                List<ContentBlock.ToolResult> budgeted = new ArrayList<>();
                int remaining = budgetBytes;
                for (ContentBlock.ToolResult r : results) {
                    if (r.content().length() <= remaining) {
                        budgeted.add(r);
                        remaining -= r.content().length();
                    } else {
                        String compacted = compactorRegistry.compact(
                                r.toolUseId(), r.content(), Math.min(2000, remaining));
                        budgeted.add(new ContentBlock.ToolResult(r.toolUseId(), compacted));
                        remaining -= compacted.length();
                    }
                }
                results = budgeted;
            }

            mutableMessages.add(UnifiedMessage.ofToolResults(results));
            // CR-095: tool_result 직후 멀티모달 user 메시지 주입 (openclaude newMessages 패턴)
            if (!injectedMessages.isEmpty()) {
                mutableMessages.addAll(injectedMessages);
            }
        }

        // CR-046: 중지된 경우 ensureTextResponse 호출 건너뛰고 partial 텍스트에 마커 부착
        if (cancelled.get()) {
            if (response != null) {
                String partialText = response.content().stream()
                        .filter(b -> b instanceof ContentBlock.Text)
                        .map(b -> ((ContentBlock.Text) b).text())
                        .reduce("", String::concat);
                response = new LLMResponse(response.id(), resolvedModel,
                        List.of(new ContentBlock.Text("[중단됨] " + partialText)),
                        List.of(), response.usage() != null ? response.usage() : new TokenUsage(0, 0),
                        LLMResponse.FinishReason.END, 0, 0);
            } else {
                response = new LLMResponse("", resolvedModel,
                        List.of(new ContentBlock.Text("[중단됨] ")),
                        List.of(), new TokenUsage(0, 0),
                        LLMResponse.FinishReason.END, 0, 0);
            }
            streamSink.accept(new StreamEvent.Done(response.usage()));
            return response;
        }

        // 5. 마지막 응답에 텍스트가 없다면 비스트리밍 한 번 더 (ensureTextResponse 재사용)
        response = ensureTextResponse(response, adapter, resolvedModel, mutableMessages, config, sessionId);
        streamSink.accept(new StreamEvent.Done(response != null ? response.usage() : null));
        return response;
    }

    /**
     * CR-045 Phase 2-B: adapter.chatStream 청크를 누적하고 streamSink로 흘리는 헬퍼.
     */
    private static final class StreamingAccumulator {
        private final java.util.function.Consumer<StreamEvent> sink;
        private final java.util.concurrent.atomic.AtomicBoolean cancelled;
        private final StringBuilder text = new StringBuilder();
        private volatile String id;
        private volatile TokenUsage usage;
        private volatile LLMResponse.FinishReason finishReason;
        private volatile List<ToolCall> toolUses;
        private final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        StreamingAccumulator(java.util.function.Consumer<StreamEvent> sink,
                             java.util.concurrent.atomic.AtomicBoolean cancelled) {
            this.sink = sink;
            this.cancelled = cancelled;
        }

        void accept(LLMStreamChunk chunk) {
            // CR-046: 중지 시 즉시 종료 (이후 chunk는 무시)
            if (cancelled.get()) {
                if (latch.getCount() > 0) latch.countDown();
                return;
            }
            if (id == null) id = chunk.id();
            if (chunk.done()) {
                usage = chunk.usage();
                finishReason = chunk.finishReason();
                toolUses = chunk.toolUses();
                latch.countDown();
                return;
            }
            if (chunk.delta() == null) return;
            if ("thinking".equals(chunk.type())) {
                sink.accept(new StreamEvent.ThinkingDelta(chunk.delta()));
            } else {
                text.append(chunk.delta());
                sink.accept(new StreamEvent.TextDelta(chunk.delta()));
            }
        }

        void await() {
            try {
                latch.await(5, java.util.concurrent.TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String id()                             { return id != null ? id : ""; }
        String text()                           { return text.toString(); }
        TokenUsage usage()                      { return usage; }
        LLMResponse.FinishReason finishReason() { return finishReason; }
        List<ToolCall> toolUses()               { return toolUses; }
    }

    /**
     * 도구 루프 종료 후 최종 응답에 Text 블록이 없으면,
     * 도구 없이 한 번 더 호출하여 텍스트 응답을 확보한다.
     */
    private LLMResponse ensureTextResponse(LLMResponse response, LLMAdapter adapter,
                                           String resolvedModel, List<UnifiedMessage> messages,
                                           ModelConfig config, String sessionId) {
        if (response == null) return response;

        boolean hasText = response.content().stream()
                .anyMatch(b -> b instanceof ContentBlock.Text t && t.text() != null && !t.text().isBlank());

        log.warn("[ensureTextResponse] hasText={}, finishReason={}, contentTypes={}, hasToolCalls={}",
                hasText, response.finishReason(),
                response.content().stream().map(b -> b.getClass().getSimpleName()).toList(),
                response.hasToolCalls());

        if (hasText) return response;

        log.warn("[ensureTextResponse] No text found — requesting final summary. messages.size={}",
                messages.size());

        // 도구 히스토리에서 tool_result 텍스트만 추출해서 새 프롬프트로 요약 요청
        StringBuilder findings = new StringBuilder();
        String originalPrompt = "";
        for (UnifiedMessage msg : messages) {
            // 첫 사용자 메시지 = 원본 프롬프트
            if (msg.role() == UnifiedMessage.Role.USER && originalPrompt.isEmpty()) {
                for (ContentBlock b : msg.content()) {
                    if (b instanceof ContentBlock.Text t) {
                        originalPrompt = t.text();
                        break;
                    }
                }
            }
            // tool_result 텍스트 수집
            for (ContentBlock b : msg.content()) {
                if (b instanceof ContentBlock.ToolResult tr && tr.content() != null && !tr.content().isBlank()) {
                    String snippet = tr.content().length() > 2000
                            ? tr.content().substring(0, 2000) + "...(truncated)"
                            : tr.content();
                    findings.append(snippet).append("\n---\n");
                }
            }
        }

        String summaryPrompt = "You previously analyzed code and gathered the following tool results.\n\n"
                + "## Original Request\n" + originalPrompt + "\n\n"
                + "## Tool Results (findings)\n" + findings + "\n\n"
                + "Now produce the final detailed text briefing based on these findings. "
                + "Include scenario names, steps, and verification points as requested.";

        log.warn("[ensureTextResponse] summary prompt length={}", summaryPrompt.length());

        List<UnifiedMessage> summaryMessages = List.of(
                UnifiedMessage.ofText(UnifiedMessage.Role.USER, summaryPrompt));

        LLMRequest finalRequest = new LLMRequest(
                resolvedModel, summaryMessages, null, config, false, sessionId, null);
        try {
            LLMResponse finalResp = adapter.chat(finalRequest).get();
            log.warn("[ensureTextResponse] final response: outputTokens={}, contentTypes={}",
                    finalResp.usage() != null ? finalResp.usage().outputTokens() : -1,
                    finalResp.content().stream().map(b -> b.getClass().getSimpleName()).toList());
            return finalResp;
        } catch (Exception e) {
            log.error("Final text summary call failed", e);
            return response;
        }
    }

    /**
     * CR-047 PRD-298: safeCalls 병렬 + unsafeCalls 순차 실행 헬퍼.
     *
     * - safeCalls: Virtual Threads + Semaphore(parallelMax) 병렬 디스패치, 예외 격리.
     * - unsafeCalls: 순차 유지 (Bash/Write/Edit 등 부수효과 있는 도구).
     * - 결과는 입력 순서를 보존하여 반환.
     * - cancelled가 비-null이면 매 호출 전 체크.
     */
    /**
     * CR-095: 도구 병렬/순차 실행. {@code injectedSink} 가 non-null 이면 각 도구의
     * ToolResult.newMessages 를 ContentBlock 으로 변환해 (toolUseId 순서대로) 추가한다.
     */
    private List<ContentBlock.ToolResult> runToolsPartitioned(
            List<ToolCall> safeCalls,
            List<ToolCall> unsafeCalls,
            ToolContext effectiveContext,
            ToolRegistry toolRegistry,
            int turnNum,
            AtomicInteger seq,
            java.util.concurrent.atomic.AtomicBoolean cancelled,
            List<UnifiedMessage> injectedSink) {

        int parallelMax = Math.max(1, platformSettings.getInt("tool.parallel-max", 10));
        Semaphore semaphore = new Semaphore(parallelMax);
        String tenantId = TenantContext.getTenantId();

        // 1) safeCalls 병렬 디스패치
        List<CompletableFuture<ToolExecOutcome>> safeFutures = new ArrayList<>(safeCalls.size());
        for (ToolCall tc : safeCalls) {
            int seqNum = seq.getAndIncrement();
            CompletableFuture<ToolExecOutcome> f = CompletableFuture.supplyAsync(() -> {
                if (cancelled != null && cancelled.get()) {
                    return ToolExecOutcome.text(tc.id(), "[중단됨] tool execution cancelled");
                }
                try {
                    semaphore.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return ToolExecOutcome.text(tc.id(), "Error: interrupted while acquiring semaphore");
                }
                try {
                    if (tenantId != null) TenantContext.setTenantId(tenantId);
                    return executeAndRecord(tc, effectiveContext, toolRegistry, turnNum, seqNum);
                } catch (Exception e) {
                    log.warn("Parallel tool {} failed: {}", tc.name(), e.getMessage());
                    return ToolExecOutcome.text(tc.id(), "Error: " + e.getMessage());
                } finally {
                    TenantContext.clear();
                    semaphore.release();
                }
            }, parallelToolExecutor).exceptionally(ex -> {
                log.warn("Parallel tool {} exception: {}", tc.name(), ex.getMessage());
                return ToolExecOutcome.text(tc.id(), "Error: " + ex.getMessage());
            });
            safeFutures.add(f);
        }

        // safeCalls 합류
        java.util.Map<String, ToolExecOutcome> byId = new java.util.HashMap<>();
        for (int i = 0; i < safeCalls.size(); i++) {
            ToolExecOutcome r;
            try {
                r = safeFutures.get(i).join();
            } catch (Exception e) {
                ToolCall tc = safeCalls.get(i);
                r = ToolExecOutcome.text(tc.id(), "Error: " + e.getMessage());
            }
            byId.put(safeCalls.get(i).id(), r);
        }

        // 2) unsafeCalls 순차 실행
        for (ToolCall tc : unsafeCalls) {
            if (cancelled != null && cancelled.get()) {
                byId.put(tc.id(), ToolExecOutcome.text(tc.id(), "[중단됨] tool execution cancelled"));
                continue;
            }
            try {
                byId.put(tc.id(), executeAndRecord(tc, effectiveContext, toolRegistry, turnNum, seq.getAndIncrement()));
            } catch (Exception e) {
                byId.put(tc.id(), ToolExecOutcome.text(tc.id(), "Error: " + e.getMessage()));
            }
        }

        // 3) 입력 순서대로 재정렬 (safe + unsafe 합쳐서). newMessages 수집.
        List<ContentBlock.ToolResult> ordered = new ArrayList<>(safeCalls.size() + unsafeCalls.size());
        List<ToolCall> all = new ArrayList<>(safeCalls.size() + unsafeCalls.size());
        all.addAll(safeCalls);
        all.addAll(unsafeCalls);
        for (ToolCall tc : all) {
            ToolExecOutcome o = byId.get(tc.id());
            ordered.add(o.result());
            if (injectedSink != null && o.injected() != null && !o.injected().isEmpty()) {
                injectedSink.add(UnifiedMessage.ofUserContent(o.injected()));
            }
        }
        return ordered;
    }

    /**
     * CR-102: 도구 루프 회차별 LLM_RESPONSE 이벤트.
     * 응답 본문만 적재 — 회차 prompt 는 이전 대화 전체의 누적 중복(용량 폭증)이라 미적재.
     * workflowRunId 없는 컨텍스트(일반 채팅)는 무동작.
     */
    private void recordLoopLlmResponse(ToolContext ctx, int iteration,
                                       LLMResponse response, String resolvedModel) {
        if (eventRecorder == null || ctx == null || ctx.workflowRunId() == null || response == null) return;
        java.util.UUID runId = parseUuidSafe(ctx.workflowRunId());
        if (runId == null) return;
        int in = response.usage() != null ? response.usage().inputTokens() : 0;
        int out = response.usage() != null ? response.usage().outputTokens() : 0;
        String finish = response.finishReason() != null ? response.finishReason().name() : null;
        String body = response.textContent();
        eventRecorder.llmResponse(runId, ctx.stepId(), iteration,
                response.model() != null ? response.model() : resolvedModel,
                in, out, finish, response.latencyMs(),
                null, parseUuidSafe(ctx.subagentRunId()),
                null, (body != null && !body.isBlank()) ? body : null,
                ctx.connectionId());
        // CR-102: CLI 어댑터가 내부에서 돈 도구 루프 관찰 (AGENT_CALL + CLI 연결 경로)
        if (response.hasObservedToolEvents()) {
            eventRecorder.observedTools(runId, ctx.stepId(), parseUuidSafe(ctx.subagentRunId()),
                    response.observedToolEvents());
        }
    }

    /**
     * CR-108: CLI 어댑터 전용 실행 — chatStream 으로 호출해 CLI 내부 도구 관찰을 turn 도중에
     * 받아 즉시 TOOL_USE/TOOL_RESULT 로 적재한다 (AGENT_CALL 진행 중 실시간 가시화).
     *
     * <p>CLI 는 도구 루프를 자기 프로세스에서 완결하므로 외부 도구 루프 반복은 없다 (1 turn).
     * tool_use(output=null) 도착 → 즉시 TOOL_USE insert + toolUseId→iteration 기억.
     * tool_result 도착 → 같은 iteration 으로 TOOL_RESULT insert (FE 페어링 키 = step+iteration+tool_name).
     * turn 끝의 observedToolEvents batch 는 이 경로에서 중복이므로 적재하지 않는다.
     */
    private LLMResponse executeCliStreaming(
            LLMAdapter adapter, String resolvedModel, List<UnifiedMessage> messages,
            ModelConfig config, String sessionId, ToolRegistry toolRegistry,
            ToolFilterContext toolFilter, ToolContext ctx, Map<String, Object> responseSchema) {

        // CLI 는 도구를 자기 MCP 채널로 받지만, allowed_tools 산출을 위해 도구 목록은 그대로 전달.
        List<UnifiedToolDef> filteredTools = toolRegistry.getToolDefs(toolFilter);
        LLMRequest request = new LLMRequest(
                resolvedModel, messages, filteredTools.isEmpty() ? null : filteredTools,
                config, true, sessionId, null, responseSchema);

        final java.util.UUID runId = (ctx != null && ctx.workflowRunId() != null)
                ? parseUuidSafe(ctx.workflowRunId()) : null;
        final java.util.UUID subRunId = ctx != null ? parseUuidSafe(ctx.subagentRunId()) : null;
        final String stepId = ctx != null ? ctx.stepId() : null;
        final boolean canRecord = eventRecorder != null && runId != null;

        StringBuilder textBuf = new StringBuilder();
        final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.Map<String, Integer> useIterByToolId = new java.util.concurrent.ConcurrentHashMap<>();
        final String[] idHolder = { null };
        final TokenUsage[] usageHolder = { null };
        final LLMResponse.FinishReason[] finishHolder = { LLMResponse.FinishReason.END };

        adapter.chatStream(request, chunk -> {
            if (chunk == null) return;
            if ("tool_use".equals(chunk.type()) && chunk.observedTool() != null && canRecord) {
                // 도착 즉시 TOOL_USE insert
                var ob = chunk.observedTool();
                int it = seq.getAndIncrement();
                if (ob.toolUseId() != null) useIterByToolId.put(ob.toolUseId(), it);
                eventRecorder.toolUse(runId, stepId, it, ob.toolName(), ob.input(), subRunId);
            } else if ("tool_result".equals(chunk.type()) && chunk.observedTool() != null && canRecord) {
                var ob = chunk.observedTool();
                Integer it = ob.toolUseId() != null ? useIterByToolId.get(ob.toolUseId()) : null;
                String out = ob.output() != null ? ob.output() : "";
                eventRecorder.toolResult(runId, stepId, it != null ? it : seq.getAndIncrement(),
                        ob.toolName(), ob.durationMs() != null ? ob.durationMs() : 0L,
                        true, null, out.length(), subRunId, out);
            } else if (chunk.delta() != null) {
                textBuf.append(chunk.delta());
            }
            if (chunk.done()) {
                if (chunk.usage() != null) usageHolder[0] = chunk.usage();
                if (chunk.finishReason() != null) finishHolder[0] = chunk.finishReason();
            }
            if (chunk.id() != null && idHolder[0] == null) idHolder[0] = chunk.id();
        });

        String text = textBuf.toString();
        LLMResponse resp = new LLMResponse(
                idHolder[0] != null ? idHolder[0] : "", resolvedModel,
                List.of(new ContentBlock.Text(text)),
                List.of(),
                usageHolder[0] != null ? usageHolder[0] : new TokenUsage(0, 0),
                finishHolder[0], 0, 0);
        // LLM_RESPONSE 만 적재 (도구는 위에서 실시간 적재 완료 — batch 중복 금지).
        if (canRecord) {
            int in = resp.usage() != null ? resp.usage().inputTokens() : 0;
            int outTok = resp.usage() != null ? resp.usage().outputTokens() : 0;
            String finish = resp.finishReason() != null ? resp.finishReason().name() : null;
            eventRecorder.llmResponse(runId, stepId, null,
                    resolvedModel, in, outTok, finish, 0L,
                    null, subRunId, null, (text != null && !text.isBlank()) ? text : null,
                    ctx.connectionId());
        }
        return resp;
    }

    private static java.util.UUID parseUuidSafe(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return java.util.UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** CR-095: 도구 1회 실행 결과 + LLM 컨텍스트에 주입할 멀티모달 블록. */
    private record ToolExecOutcome(ContentBlock.ToolResult result, List<ContentBlock> injected) {
        static ToolExecOutcome text(String toolUseId, String content) {
            return new ToolExecOutcome(new ContentBlock.ToolResult(toolUseId, content), List.of());
        }
    }

    /** CR-095: 경량 ToolMessageBlock → platform-core ContentBlock 변환. */
    private static List<ContentBlock> toContentBlocks(List<ToolMessageBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return List.of();
        List<ContentBlock> out = new ArrayList<>(blocks.size());
        for (ToolMessageBlock b : blocks) {
            switch (b.type()) {
                case ToolMessageBlock.TYPE_DOCUMENT ->
                        out.add(ContentBlock.Document.ofBase64(b.mediaType(), b.data(), b.filename()));
                case ToolMessageBlock.TYPE_IMAGE ->
                        out.add(ContentBlock.Image.ofBase64(b.mediaType(), b.data()));
                case ToolMessageBlock.TYPE_TEXT ->
                        out.add(new ContentBlock.Text(b.data()));
                default -> log.warn("CR-095: unknown ToolMessageBlock type={}, skipped", b.type());
            }
        }
        return out;
    }

    /**
     * 단일 도구 실행 + lineage 기록 + LLM용 결과 생성.
     * CR-030: PreToolUse / PostToolUse / PostToolUseFailure 훅 삽입.
     */
    private ToolExecOutcome executeAndRecord(ToolCall tc, ToolContext toolContext,
                                              ToolRegistry toolRegistry, int turnNum, int seqNum) {
        log.debug("Executing tool: {} (id={}, turn={}, seq={})",
                tc.name(), tc.id(), turnNum, seqNum);

        // CR-033 BIZ-052: Plan Mode 읽기전용 검사
        if (toolContext != null && toolContext.sessionId() != null) {
            String planModeKey = "session:planMode:" + toolContext.sessionId();
            if ("true".equals(redisTemplate.opsForValue().get(planModeKey))
                    && !PLAN_MODE_ALLOWED_WRITES.contains(tc.name())) {
                ToolContractMeta meta = toolRegistry.getContractMeta(tc.name());
                if (meta != null && !meta.readOnly()) {
                    log.info("Plan mode: blocked write tool={}, session={}", tc.name(), toolContext.sessionId());
                    return ToolExecOutcome.text(tc.id(),
                            "[DENIED] Plan mode active: only read-only tools allowed. " +
                                    "Use exit_plan_mode to enable writes.");
                }
            }
        }

        // PRD-193: PreToolUse 훅
        HookOutput preHook = hookDispatcher.dispatch(
                HookEvent.PRE_TOOL_USE,
                HookInput.of(HookEvent.PRE_TOOL_USE, toolContext.sessionId(), tc.name(), tc.input()),
                tc.name());
        if (preHook.decision() == HookDecision.BLOCK) {
            log.info("Tool execution blocked by hook: tool={}, turn={}", tc.name(), turnNum);
            return ToolExecOutcome.text(tc.id(), "Tool execution blocked by policy hook");
        }

        // CR-102: TOOL_USE 이벤트 — 워크플로우 run 컨텍스트일 때만 (input 전문 적재)
        if (eventRecorder != null && toolContext != null && toolContext.workflowRunId() != null) {
            java.util.UUID runId = parseUuidSafe(toolContext.workflowRunId());
            if (runId != null) {
                eventRecorder.toolUse(runId, toolContext.stepId(), turnNum, tc.name(),
                        tc.input(), parseUuidSafe(toolContext.subagentRunId()));
            }
        }

        ToolResult toolResult;
        try {
            toolResult = toolRegistry.execute(tc, toolContext);
        } catch (Exception e) {
            // PRD-193: PostToolUseFailure 훅
            hookDispatcher.dispatch(
                    HookEvent.POST_TOOL_USE_FAILURE,
                    HookInput.of(HookEvent.POST_TOOL_USE_FAILURE, toolContext.sessionId(), tc.name(),
                            Map.of("error", e.getMessage() != null ? e.getMessage() : "unknown")),
                    tc.name());
            throw e;
        }

        recordLineage(toolContext, tc, toolResult, turnNum, seqNum);

        // CR-102: TOOL_RESULT 이벤트 — output 전문 적재 (워크플로우 run 컨텍스트일 때만)
        if (eventRecorder != null && toolContext != null && toolContext.workflowRunId() != null) {
            java.util.UUID runId = parseUuidSafe(toolContext.workflowRunId());
            if (runId != null) {
                String outputFull = toolResult.output() != null ? toolResult.output().toString() : null;
                eventRecorder.toolResult(runId, toolContext.stepId(), turnNum, tc.name(),
                        toolResult.durationMs(), toolResult.success(),
                        toolResult.success() ? null : toolResult.summary(),
                        outputFull != null ? outputFull.length() : 0,
                        parseUuidSafe(toolContext.subagentRunId()),
                        outputFull);
            }
        }

        // PRD-193: PostToolUse / PostToolUseFailure 훅
        if (toolResult.success()) {
            hookDispatcher.dispatch(
                    HookEvent.POST_TOOL_USE,
                    HookInput.of(HookEvent.POST_TOOL_USE, toolContext.sessionId(), tc.name(),
                            Map.of("result", toolResult.summary() != null ? toolResult.summary() : "")),
                    tc.name());
        } else {
            hookDispatcher.dispatch(
                    HookEvent.POST_TOOL_USE_FAILURE,
                    HookInput.of(HookEvent.POST_TOOL_USE_FAILURE, toolContext.sessionId(), tc.name(),
                            Map.of("error", toolResult.summary() != null ? toolResult.summary() : "unknown")),
                    tc.name());
        }

        // LLM에 output 전달 — OpenClaude 패턴: 큰 결과는 preview로 대체
        String resultText;
        if (!toolResult.success()) {
            resultText = "Error: " + toolResult.summary();
        } else if (toolResult.output() != null) {
            // 전체 전달 — per-message budget이 총량 제어
            resultText = toolResult.output().toString();
        } else {
            resultText = toolResult.summary();
        }
        // CR-095: 도구가 멀티모달 블록(PDF document / 페이지 이미지)을 주입 요청했으면 변환해 동봉
        List<ContentBlock> injected = toContentBlocks(toolResult.newMessages());
        return new ToolExecOutcome(new ContentBlock.ToolResult(tc.id(), resultText), injected);
    }

    /**
     * CR-029: 도구 실행 lineage를 DB에 비동기 기록.
     * Virtual Thread로 분리해 메인 요청 흐름의 지연을 제거.
     * TenantContext(ThreadLocal)는 호출 시점에 캡처 후 새 스레드에 전달.
     */
    private void recordLineage(ToolContext ctx, ToolCall call, ToolResult result,
                                int turnNumber, int sequenceInTurn) {
        ToolExecutionLogEntity logEntity = new ToolExecutionLogEntity();
        logEntity.setSessionId(ctx.sessionId() != null ? ctx.sessionId() : "unknown");
        logEntity.setWorkflowRunId(ctx.workflowRunId());
        logEntity.setStepId(ctx.stepId());
        logEntity.setTurnNumber(turnNumber);
        logEntity.setSequenceInTurn(sequenceInTurn);
        logEntity.setToolId(call.name());
        logEntity.setToolName(call.name());
        logEntity.setInputSummary(truncate(call.input() != null ? call.input().toString() : "", 500));
        logEntity.setInputFull(call.input());
        logEntity.setOutputSummary(truncate(result.summary() != null ? result.summary() : "", 500));
        logEntity.setOutputFull(result.output() != null ? result.output().toString() : null);
        logEntity.setSuccess(result.success());
        logEntity.setDurationMs((int) result.durationMs());
        // C3: denied vs error vs native 구분 기록
        logEntity.setRuntimeKind(result.isDenied() ? "denied" : "native");

        String tenantId = TenantContext.getTenantId();
        Thread.ofVirtual().start(() -> {
            try {
                if (tenantId != null) TenantContext.setTenantId(tenantId);
                executionLogRepository.save(logEntity);
            } catch (Exception e) {
                log.warn("Failed to record tool execution lineage: {}", e.getMessage());
            } finally {
                TenantContext.clear();
            }
        });
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 기존 호환용 메서드 (toolFilter, toolChoice 없음).
     */
    public LLMResponse executeLoop(
            LLMAdapter adapter,
            String resolvedModel,
            List<UnifiedMessage> messages,
            ModelConfig config,
            String sessionId,
            ToolRegistry toolRegistry) {
        return executeLoop(adapter, resolvedModel, messages, config, sessionId, toolRegistry, null, null);
    }
}
