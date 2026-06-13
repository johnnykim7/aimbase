package com.platform.tool;

import com.platform.config.PlatformSettingsService;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookOutput;
import com.platform.policy.PermissionClassifier;
import com.platform.repository.ToolExecutionLogRepository;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.ModelConfig;
import com.platform.llm.model.ObservedToolEvent;
import com.platform.llm.model.TokenUsage;
import com.platform.llm.model.ToolCall;
import com.platform.llm.model.UnifiedMessage;
import com.platform.tool.compact.ToolResultCompactorRegistry;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.registry.SessionToolRegistry;
import com.platform.tool.storage.ToolResultStorageService;
import com.platform.workflow.event.WorkflowRunEventRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CR-102: ToolCallHandler 도구 루프의 워크플로우 run 이벤트 훅 검증.
 *
 * <p>workflowRunId 가 있는 ToolContext(=AGENT_CALL 서브에이전트 경로)일 때만
 * LLM_RESPONSE(회차별, 응답만) + TOOL_USE/TOOL_RESULT(전문) 이벤트가 적재되고,
 * 없는 컨텍스트(일반 채팅)는 무동작이어야 한다.
 */
@DisplayName("ToolCallHandler — CR-102 run 이벤트 훅")
class ToolCallHandlerCr102Test {

    private ToolCallHandler handler;
    private WorkflowRunEventRecorder recorder;
    private LLMAdapter adapter;
    private ToolRegistry toolRegistry;

    private final UUID runId = UUID.randomUUID();
    private final UUID subagentRunId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        recorder = mock(WorkflowRunEventRecorder.class);
        adapter = mock(LLMAdapter.class);
        toolRegistry = mock(ToolRegistry.class);

        HookDispatcher hooks = mock(HookDispatcher.class);
        when(hooks.dispatch(any(), any(), anyString())).thenReturn(HookOutput.PASSTHROUGH);

        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));

        handler = new ToolCallHandler(
                mock(ToolExecutionLogRepository.class),
                hooks,
                mock(PermissionClassifier.class),
                mock(ToolResultCompactorRegistry.class),
                redis,
                5,
                settings,
                mock(SessionToolRegistry.class),
                mock(ToolResultStorageService.class),
                recorder);
    }

    private ToolContext workflowContext() {
        return new ToolContext(
                "tenant-a", null, null, "sess-1",
                runId.toString(), "step1", subagentRunId.toString(),
                null, PermissionLevel.FULL, ApprovalState.NOT_REQUIRED,
                null, false, 0);
    }

    private LLMResponse toolUseResponse() {
        return new LLMResponse("r1", "claude-x",
                List.of(new ContentBlock.Text("도구를 호출합니다")),
                List.of(new ToolCall("t1", "calc", Map.of("expr", "1+1"))),
                new TokenUsage(10, 5), LLMResponse.FinishReason.TOOL_USE, 100L, 0.0);
    }

    private LLMResponse endResponse() {
        return new LLMResponse("r2", "claude-x",
                List.of(new ContentBlock.Text("결과는 2 입니다")),
                List.of(), new TokenUsage(20, 7), LLMResponse.FinishReason.END, 200L, 0.0);
    }

    @Test
    @DisplayName("workflowRunId 있는 컨텍스트 → 회차별 LLM_RESPONSE + TOOL_USE/TOOL_RESULT 이벤트 적재")
    void recordsEventsForWorkflowContext() {
        when(toolRegistry.getToolDefs(any())).thenReturn(
                List.of(new UnifiedToolDef("calc", "계산기", Map.of())));
        when(toolRegistry.execute(any(ToolCall.class), any(ToolContext.class))).thenReturn(
                new ToolResult(true, "2", "ok", List.of(), List.of(), Map.of(), null, 42L));
        when(adapter.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolUseResponse()))
                .thenReturn(CompletableFuture.completedFuture(endResponse()));

        handler.executeLoop(adapter, "claude-x",
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "1+1은?")),
                ModelConfig.defaults(), "sess-1", toolRegistry, null, null,
                workflowContext(), null);

        // 회차 0 (tool_use) + 회차 1 (end) — 응답 본문만 (prompt null)
        verify(recorder).llmResponse(eq(runId), eq("step1"), eq(0), eq("claude-x"),
                eq(10), eq(5), eq("TOOL_USE"), eq(100L), isNull(), eq(subagentRunId),
                isNull(), eq("도구를 호출합니다"), isNull());
        verify(recorder).llmResponse(eq(runId), eq("step1"), eq(1), eq("claude-x"),
                eq(20), eq(7), eq("END"), eq(200L), isNull(), eq(subagentRunId),
                isNull(), eq("결과는 2 입니다"), isNull());
        // 도구 1회: TOOL_USE(input 전문) + TOOL_RESULT(output 전문)
        verify(recorder).toolUse(eq(runId), eq("step1"), eq(0), eq("calc"),
                eq(Map.of("expr", "1+1")), eq(subagentRunId));
        verify(recorder).toolResult(eq(runId), eq("step1"), eq(0), eq("calc"),
                eq(42L), eq(true), isNull(), eq(1), eq(subagentRunId), eq("2"));
    }

    @Test
    @DisplayName("workflowRunId 없는 컨텍스트(일반 채팅) → 이벤트 무적재")
    void noEventsWithoutWorkflowRunId() {
        when(toolRegistry.getToolDefs(any())).thenReturn(
                List.of(new UnifiedToolDef("calc", "계산기", Map.of())));
        when(toolRegistry.execute(any(ToolCall.class), any(ToolContext.class))).thenReturn(
                new ToolResult(true, "2", "ok", List.of(), List.of(), Map.of(), null, 42L));
        when(adapter.chat(any()))
                .thenReturn(CompletableFuture.completedFuture(toolUseResponse()))
                .thenReturn(CompletableFuture.completedFuture(endResponse()));

        handler.executeLoop(adapter, "claude-x",
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "1+1은?")),
                ModelConfig.defaults(), "sess-1", toolRegistry, null, null,
                ToolContext.minimal("tenant-a", "sess-1"), null);

        verifyNoInteractions(recorder);
    }

    @Test
    @DisplayName("CLI 어댑터 관찰(observedToolEvents) → observedTools 일괄 적재 (도구 없는 단발 경로 포함)")
    void recordsObservedToolEventsFromCliAdapter() {
        // 도구 필터 결과가 비어 단발 호출로 빠지는 경로 — CLI 어댑터 응답에 관찰 동봉
        when(toolRegistry.getToolDefs(any())).thenReturn(List.of());
        List<ObservedToolEvent> observed = List.of(
                new ObservedToolEvent("web_search", Map.of("query", "aimbase"), "검색 결과 전문", 1234L));
        LLMResponse cliResponse = new LLMResponse("r3", "anthropic-cli",
                List.of(new ContentBlock.Text("최종 답")),
                List.of(), new TokenUsage(30, 9), LLMResponse.FinishReason.END, 5000L, 0.0,
                observed);
        when(adapter.chat(any())).thenReturn(CompletableFuture.completedFuture(cliResponse));

        LLMResponse result = handler.executeLoop(adapter, "anthropic-cli",
                List.of(UnifiedMessage.ofText(UnifiedMessage.Role.USER, "검색해줘")),
                ModelConfig.defaults(), "sess-1", toolRegistry, null, null,
                workflowContext(), null);

        assertThat(result.textContent()).isEqualTo("최종 답");
        verify(recorder).llmResponse(eq(runId), eq("step1"), eq(0), eq("anthropic-cli"),
                eq(30), eq(9), eq("END"), eq(5000L), isNull(), eq(subagentRunId),
                isNull(), eq("최종 답"), isNull());
        verify(recorder).observedTools(eq(runId), eq("step1"), eq(subagentRunId), eq(observed));
    }
}
