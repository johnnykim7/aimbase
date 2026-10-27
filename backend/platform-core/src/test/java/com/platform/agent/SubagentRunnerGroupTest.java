package com.platform.agent;

import com.platform.hook.HookDispatcher;
import com.platform.hook.HookOutput;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.ConnectionGroupSelector;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.TokenUsage;
import com.platform.orchestrator.ChatRequest;
import com.platform.orchestrator.ChatResponse;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.repository.SubagentRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** CR-161 TC-CG-116: AGENT_CALL 의 connection_group_id 가 자식 채팅으로 가고, 실행기 정리는 그룹 멤버 전부를 대상으로 한다. */
@ExtendWith(MockitoExtension.class)
class SubagentRunnerGroupTest {

    @Mock private OrchestratorEngine orchestratorEngine;
    @Mock private SubagentRunRepository subagentRunRepository;
    @Mock private WorktreeManager worktreeManager;
    @Mock private HookDispatcher hookDispatcher;
    @Mock private SubagentLifecycleManager lifecycleManager;
    @Mock private AgentTypeRegistry agentTypeRegistry;
    @Mock private PlanService planService;
    @Mock private ConnectionAdapterFactory connectionAdapterFactory;
    @Mock private ConnectionGroupSelector groupSelector;
    @Mock private LLMAdapter w01;
    @Mock private LLMAdapter w02;

    private final ActiveCliWorkerRegistry registry = new ActiveCliWorkerRegistry();
    private SubagentRunner runner;

    @BeforeEach
    void setUp() {
        runner = new SubagentRunner(orchestratorEngine, subagentRunRepository,
                worktreeManager, hookDispatcher, lifecycleManager, agentTypeRegistry, planService, connectionAdapterFactory,
                registry, groupSelector);

        lenient().when(hookDispatcher.dispatch(any(), any())).thenReturn(HookOutput.PASSTHROUGH);
        lenient().when(subagentRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(agentTypeRegistry.getConfig(any()))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig(AgentType.GENERAL, "범용 에이전트", null, false));
        when(groupSelector.memberConnectionIds("grp")).thenReturn(List.of("w01", "w02"));
        when(connectionAdapterFactory.getAdapter("w01")).thenReturn(w01);
        when(connectionAdapterFactory.getAdapter("w02")).thenReturn(w02);
    }

    @Test
    void tc_cg_116_childChatCarriesGroupAndCleanupCoversMembers() {
        AtomicReference<ChatRequest> sent = new AtomicReference<>();
        List<ActiveCliWorkerRegistry.WorkerRef> registeredDuringCall = new ArrayList<>();
        when(orchestratorEngine.chat(any())).thenAnswer(inv -> {
            sent.set(inv.getArgument(0));
            registeredDuringCall.addAll(registry.workersOf("run-1"));
            return new ChatResponse("resp-1", "claude-sonnet", "child-sess",
                    List.of(new ContentBlock.Text("결과")), List.of(), new TokenUsage(1, 1), 0.0);
        });

        SubagentRequest request = new SubagentRequest(
                "agent", "작업", null, "conn-ignored",
                SubagentRequest.IsolationMode.NONE, false, 30_000,
                Map.of("connection_group_id", "grp"), "parent-sess", AgentType.GENERAL,
                "run-1", "step-1", null, null);

        SubagentResult result = runner.run(request);

        assertThat(result.status()).isEqualTo(SubagentResult.Status.COMPLETED);
        assertThat(sent.get().connectionGroupId()).isEqualTo("grp");
        assertThat(registeredDuringCall).extracting(ActiveCliWorkerRegistry.WorkerRef::connectionId)
                .containsExactlyInAnyOrder("w01", "w02");
        assertThat(registry.workersOf("run-1")).isEmpty();
        verify(w01).cleanupSession(eq(result.sessionId()));
        verify(w02).cleanupSession(eq(result.sessionId()));
        verify(connectionAdapterFactory, never()).getAdapter("conn-ignored");
    }
}
