package com.platform.tool;

import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
    private static final int MAX_ITERATIONS = 5;

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
