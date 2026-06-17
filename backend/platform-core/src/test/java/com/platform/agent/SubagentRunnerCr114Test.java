package com.platform.agent;

import com.platform.hook.HookDispatcher;
import com.platform.hook.HookOutput;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.TokenUsage;
import com.platform.orchestrator.ChatResponse;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.repository.SubagentRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CR-114: AGENT_CALL 정상 완료 경로의 CLI worker 결정적 정리 단위 테스트.
 *
 * <p>검증: ① 정상 완료 + connectionId 있음 → 해당 어댑터 cleanupSession(childSessionId) 호출(좀비 누수 차단).
 * ② resume(timeout retry 이어하기) 대상 → cleanup 미호출(살아있는 워커 보호). ③ connectionId 없음(modelRouter 라우팅)
 * → cleanup 미호출(worker pool 자체 없음). ④ CLI 외 어댑터는 cleanupSession 기본 no-op 이라 worker 없는데 cancel 부르는
 * 오류가 없다(LLMAdapter default 동작).
 */
@ExtendWith(MockitoExtension.class)
class SubagentRunnerCr114Test {

    @Mock private OrchestratorEngine orchestratorEngine;
    @Mock private SubagentRunRepository subagentRunRepository;
    @Mock private WorktreeManager worktreeManager;
    @Mock private HookDispatcher hookDispatcher;
    @Mock private SubagentLifecycleManager lifecycleManager;
    @Mock private AgentTypeRegistry agentTypeRegistry;
    @Mock private PlanService planService;
    @Mock private ConnectionAdapterFactory connectionAdapterFactory;
    @Mock private LLMAdapter adapter;

    private SubagentRunner runner;

    @BeforeEach
    void setUp() {
        runner = new SubagentRunner(orchestratorEngine, subagentRunRepository,
                worktreeManager, hookDispatcher, lifecycleManager, agentTypeRegistry, planService, connectionAdapterFactory,
                new com.platform.agent.ActiveCliWorkerRegistry());

        lenient().when(hookDispatcher.dispatch(any(), any())).thenReturn(HookOutput.PASSTHROUGH);
        lenient().when(subagentRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(agentTypeRegistry.getConfig(any()))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig(
                        AgentType.GENERAL, "범용 에이전트", null, false));

        ChatResponse chatResponse = new ChatResponse(
                "resp-1", "claude-sonnet", "child-sess",
                List.of(new ContentBlock.Text("결과 텍스트")),
                List.of(), new TokenUsage(100, 200), 0.01);
        lenient().when(orchestratorEngine.chat(any())).thenReturn(chatResponse);
    }

    /** connectionId/resumeSessionId 를 명시 지정한 full record 생성자 헬퍼. */
    private SubagentRequest request(String connectionId, String resumeSessionId) {
        return new SubagentRequest(
                "test agent", "작업을 수행해줘", null, connectionId,
                SubagentRequest.IsolationMode.NONE, false, 30_000,
                Map.of(), "parent-sess-1", AgentType.GENERAL,
                null, null, resumeSessionId, null);
    }

    @Test
    void normalCompletion_withConnectionId_cleansUpWorker() {
        when(connectionAdapterFactory.getAdapter("conn-cli")).thenReturn(adapter);

        SubagentResult result = runner.run(request("conn-cli", null));

        assertThat(result.status()).isEqualTo(SubagentResult.Status.COMPLETED);
        // childSessionId 는 새 UUID("subagent-...")로 파생 — 정확히 그 값으로 cleanup 되었는지 확인.
        verify(connectionAdapterFactory).getAdapter("conn-cli");
        verify(adapter).cleanupSession(eq(result.sessionId()));
    }

    @Test
    void resumeSession_doesNotCleanUp() {
        // resume 이어하기 대상 — 살아있는 워커를 다음 retry 가 재사용하므로 닫지 않는다.
        SubagentResult result = runner.run(request("conn-cli", "subagent-resume-fixed"));

        assertThat(result.status()).isEqualTo(SubagentResult.Status.COMPLETED);
        verify(connectionAdapterFactory, never()).getAdapter(any());
        verify(adapter, never()).cleanupSession(any());
    }

    @Test
    void noConnectionId_doesNotCleanUp() {
        // connectionId 없음 → modelRouter 라우팅(CLI 아님) → worker pool 자체가 없으므로 정리 시도 안 함.
        SubagentResult result = runner.run(request(null, null));

        assertThat(result.status()).isEqualTo(SubagentResult.Status.COMPLETED);
        verify(connectionAdapterFactory, never()).getAdapter(any());
    }

    @Test
    void nonCliAdapter_cleanupIsNoOp() {
        // CLI 외 어댑터: cleanupSession 은 LLMAdapter default no-op — 예외 없이 통과(worker 없는데 cancel 부르는 오류 없음).
        LLMAdapter realDefaultAdapter = new LLMAdapter() {
            @Override public String getProvider() { return "anthropic"; }
            @Override public List<String> getSupportedModels() { return List.of(); }
            @Override public java.util.concurrent.CompletableFuture<com.platform.llm.model.LLMResponse> chat(
                    com.platform.llm.model.LLMRequest r) { return null; }
            @Override public void chatStream(com.platform.llm.model.LLMRequest r,
                    java.util.function.Consumer<com.platform.llm.model.LLMStreamChunk> c) { }
            @Override public Object transformToolDefs(List<com.platform.tool.model.UnifiedToolDef> t) { return null; }
            @Override public List<com.platform.llm.model.ToolCall> parseToolCalls(Object n) { return List.of(); }
            // cleanupSession 미오버라이드 → default no-op
        };
        when(connectionAdapterFactory.getAdapter("conn-anthropic")).thenReturn(realDefaultAdapter);

        SubagentResult result = runner.run(request("conn-anthropic", null));

        // no-op 이 호출돼도 예외 없이 정상 완료.
        assertThat(result.status()).isEqualTo(SubagentResult.Status.COMPLETED);
        verify(connectionAdapterFactory).getAdapter("conn-anthropic");
    }
}
