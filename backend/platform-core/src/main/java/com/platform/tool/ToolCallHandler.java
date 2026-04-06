package com.platform.tool;

import com.platform.domain.ToolExecutionLogEntity;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import com.platform.repository.ToolExecutionLogRepository;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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

    public ToolCallHandler(ToolExecutionLogRepository executionLogRepository) {
        this.executionLogRepository = executionLogRepository;
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

            // 2. 각 도구 실행 → tool_result 메시지 기록
            List<ContentBlock.ToolResult> results = response.toolCalls().stream()
                    .map(tc -> {
                        log.debug("Executing tool: {} (id={})", tc.name(), tc.id());
                        String result = toolRegistry.execute(tc);
                        return new ContentBlock.ToolResult(tc.id(), result);
                    })
                    .toList();
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
                results.add(executeAndRecord(tc, toolContext, toolRegistry, turnNum, seq.getAndIncrement()));
            }
            // 2) unsafe 도구들 (순차)
            for (ToolCall tc : unsafeCalls) {
                results.add(executeAndRecord(tc, toolContext, toolRegistry, turnNum, seq.getAndIncrement()));
            }

            // A2: per-message budget (OpenClaude: 3MB, 여기선 50KB)
            int totalChars = results.stream().mapToInt(r -> r.content().length()).sum();
            if (totalChars > 80_000) {
                log.debug("Tool results total {}KB exceeds 80KB budget, truncating", totalChars / 1000);
                results.sort((a, b) -> b.content().length() - a.content().length());
                List<ContentBlock.ToolResult> budgeted = new ArrayList<>();
                int remaining = 50_000;
                for (ContentBlock.ToolResult r : results) {
                    if (r.content().length() <= remaining) {
                        budgeted.add(r);
                        remaining -= r.content().length();
                    } else {
                        String p = r.content().substring(0, Math.min(2000, r.content().length()));
                        int nl = p.lastIndexOf('\n');
                        if (nl > 1000) p = p.substring(0, nl);
                        budgeted.add(new ContentBlock.ToolResult(r.toolUseId(),
                                p + "\n...(truncated for context budget)"));
                        remaining -= p.length() + 40;
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
     */
    private ContentBlock.ToolResult executeAndRecord(ToolCall tc, ToolContext toolContext,
                                                      ToolRegistry toolRegistry, int turnNum, int seqNum) {
        log.debug("Executing tool: {} (id={}, turn={}, seq={})",
                tc.name(), tc.id(), turnNum, seqNum);

        ToolResult toolResult = toolRegistry.execute(tc, toolContext);
        recordLineage(toolContext, tc, toolResult, turnNum, seqNum);

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
        logEntity.setRuntimeKind("native");

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
