package com.platform.tool;

import com.platform.domain.ToolExecutionLogEntity;
import com.platform.hook.HookDecision;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookEvent;
import com.platform.hook.HookInput;
import com.platform.hook.HookOutput;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
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
 *    d. 재호출 (최대 MAX_ITERATIONS회)
 * 3. END 또는 max 도달 시 최종 LLMResponse 반환
 */
@Component
public class ToolCallHandler {

    private static final Logger log = LoggerFactory.getLogger(ToolCallHandler.class);
    private static final int MAX_ITERATIONS = 15;

    private final ToolExecutionLogRepository executionLogRepository;
    private final HookDispatcher hookDispatcher;
    private final PermissionClassifier permissionClassifier;
    private final com.platform.tool.compact.ToolResultCompactorRegistry compactorRegistry;
    private final RedisTemplate<String, String> redisTemplate;

    /** CR-033: Plan Mode 읽기전용 검사에서 허용하는 도구 (Plan Mode 자체 제어용) */
    private static final java.util.Set<String> PLAN_MODE_ALLOWED_WRITES =
            java.util.Set.of("exit_plan_mode", "todo_write");

    public ToolCallHandler(ToolExecutionLogRepository executionLogRepository,
                           HookDispatcher hookDispatcher,
                           PermissionClassifier permissionClassifier,
                           com.platform.tool.compact.ToolResultCompactorRegistry compactorRegistry,
                           RedisTemplate<String, String> redisTemplate) {
        this.executionLogRepository = executionLogRepository;
        this.hookDispatcher = hookDispatcher;
        this.permissionClassifier = permissionClassifier;
        this.compactorRegistry = compactorRegistry;
        this.redisTemplate = redisTemplate;
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
        List<UnifiedToolDef> filteredTools = toolRegistry.getToolDefs(toolFilter);

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

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
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

        return response;
    }

    /**
     * CR-029: ToolContext 기반 실행 루프.
     * 기존 executeLoop와 동일하되, EnhancedToolExecutor 분기 + lineage 기록.
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

        List<UnifiedMessage> mutableMessages = new ArrayList<>(messages);
        LLMResponse response = null;
        List<UnifiedToolDef> filteredTools = toolRegistry.getToolDefs(toolFilter);

        if (filteredTools.isEmpty()) {
            log.debug("No tools available after filtering, executing without tools");
            LLMRequest request = new LLMRequest(
                    resolvedModel, mutableMessages, null, config, false, sessionId, null);
            try {
                return adapter.chat(request).get();
            } catch (Exception e) {
                throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
            }
        }

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            LLMRequest request = new LLMRequest(
                    resolvedModel, mutableMessages, filteredTools,
                    config, false, sessionId, toolChoice);

            try {
                response = adapter.chat(request).get();
            } catch (Exception e) {
                log.error("LLM call failed during tool loop (iteration {})", iteration, e);
                throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
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

            // 안전한 도구는 병렬 실행 가능 (현재는 순차지만 향후 CompletableFuture로 전환)
            List<ContentBlock.ToolResult> results = new ArrayList<>();

            // 1) concurrencySafe 도구들
            for (ToolCall tc : safeCalls) {
                results.add(executeAndRecord(tc, effectiveContext, toolRegistry, turnNum, seq.getAndIncrement()));
            }
            // 2) unsafe 도구들 (순차)
            for (ToolCall tc : unsafeCalls) {
                results.add(executeAndRecord(tc, effectiveContext, toolRegistry, turnNum, seq.getAndIncrement()));
            }

            // A2 + CR-031 PRD-215: per-message budget + 도구별 지능형 축약
            int totalChars = results.stream().mapToInt(r -> r.content().length()).sum();
            if (totalChars > 80_000) {
                log.debug("Tool results total {}KB exceeds 80KB budget, applying smart compaction", totalChars / 1000);
                results.sort((a, b) -> b.content().length() - a.content().length());
                List<ContentBlock.ToolResult> budgeted = new ArrayList<>();
                int remaining = 50_000;
                for (ContentBlock.ToolResult r : results) {
                    if (r.content().length() <= remaining) {
                        budgeted.add(r);
                        remaining -= r.content().length();
                    } else {
                        // CR-031: 도구별 지능형 축약 적용
                        String compacted = compactorRegistry.compact(
                                r.toolUseId(), r.content(), Math.min(2000, remaining));
                        budgeted.add(new ContentBlock.ToolResult(r.toolUseId(), compacted));
                        remaining -= compacted.length();
                    }
                }
                results = budgeted;
            }

            mutableMessages.add(UnifiedMessage.ofToolResults(results));
        }

        return response;
    }

    /**
     * 단일 도구 실행 + lineage 기록 + LLM용 결과 생성.
     * CR-030: PreToolUse / PostToolUse / PostToolUseFailure 훅 삽입.
     */
    private ContentBlock.ToolResult executeAndRecord(ToolCall tc, ToolContext toolContext,
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
                    return new ContentBlock.ToolResult(tc.id(),
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
            return new ContentBlock.ToolResult(tc.id(), "Tool execution blocked by policy hook");
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
        return new ContentBlock.ToolResult(tc.id(), resultText);
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
