package com.platform.tool;

import com.platform.domain.ToolExecutionLogEntity;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import com.platform.repository.ToolExecutionLogRepository;
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

            // CR-029: ToolContext 기반 실행 + lineage 기록
            final int turnNum = iteration;
            AtomicInteger seq = new AtomicInteger(0);
            List<ContentBlock.ToolResult> results = response.toolCalls().stream()
                    .map(tc -> {
                        int seqNum = seq.getAndIncrement();
                        log.debug("Executing tool: {} (id={}, turn={}, seq={})",
                                tc.name(), tc.id(), turnNum, seqNum);

                        ToolResult toolResult = toolRegistry.execute(tc, toolContext);
                        recordLineage(toolContext, tc, toolResult, turnNum, seqNum);

                        // LLM에 output 전달 — OpenClaude 패턴: 큰 결과는 preview로 대체
                        String resultText;
                        if (!toolResult.success()) {
                            resultText = "Error: " + toolResult.summary();
                        } else if (toolResult.output() != null) {
                            String outputStr = toolResult.output().toString();
                            if (outputStr.length() <= 4_000) {
                                // 4KB 이하: 전체 전달
                                resultText = outputStr;
                            } else {
                                // 4KB 초과: preview(첫 2KB) + truncation 안내
                                String preview = outputStr.substring(0, Math.min(2000, outputStr.length()));
                                // 줄 경계에서 자르기
                                int lastNewline = preview.lastIndexOf('\n');
                                if (lastNewline > 1000) preview = preview.substring(0, lastNewline);
                                resultText = toolResult.summary() + "\n\n"
                                        + "Preview (first 2KB of " + outputStr.length() + " chars):\n"
                                        + preview + "\n...(truncated)";
                            }
                        } else {
                            resultText = toolResult.summary();
                        }
                        return new ContentBlock.ToolResult(tc.id(), resultText);
                    })
                    .toList();
            mutableMessages.add(UnifiedMessage.ofToolResults(results));
        }

        return response;
    }

    /**
     * CR-029: 도구 실행 lineage를 DB에 기록.
     */
    private void recordLineage(ToolContext ctx, ToolCall call, ToolResult result,
                                int turnNumber, int sequenceInTurn) {
        try {
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
            executionLogRepository.save(logEntity);
        } catch (Exception e) {
            log.warn("Failed to record tool execution lineage: {}", e.getMessage());
        }
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
