package com.platform.agent;

import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.TokenUsage;
import com.platform.orchestrator.ChatResponse;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookOutput;
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
import static org.mockito.Mockito.*;

/**
 * CR-030 PRD-209/210: AgentOrchestrator 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    @Mock private OrchestratorEngine orchestratorEngine;
    @Mock private SubagentRunRepository subagentRunRepository;
    @Mock private WorktreeManager worktreeManager;
    @Mock private HookDispatcher hookDispatcher;
    @Mock private SubagentLifecycleManager lifecycleManager;
    @Mock private AgentTypeRegistry agentTypeRegistry;

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        lenient().when(hookDispatcher.dispatch(any(), any())).thenReturn(HookOutput.PASSTHROUGH);
        lenient().when(subagentRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // CR-034: AgentTypeRegistry mock
        lenient().when(agentTypeRegistry.getConfig(any()))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig(
                        AgentType.GENERAL, "범용 에이전트", null, false));

        SubagentRunner runner = new SubagentRunner(orchestratorEngine, subagentRunRepository,
                worktreeManager, hookDispatcher, lifecycleManager, agentTypeRegistry);
        orchestrator = new AgentOrchestrator(runner);
    }

    private SubagentRequest makeRequest(String desc, String prompt) {
        return new SubagentRequest(desc, prompt, null, null,
                SubagentRequest.IsolationMode.NONE, false, 30_000,
                Map.of(), "parent-1");
    }

    private void mockChatResponse(String text) {
        ChatResponse response = new ChatResponse(
                "resp", "claude-sonnet", "child",
                List.of(new ContentBlock.Text(text)),
                List.of(), new TokenUsage(50, 100), 0.005);
        when(orchestratorEngine.chat(any())).thenReturn(response);
    }

    @Test
    void runSingle_success() {
        mockChatResponse("단일 결과");

        SubagentResult result = orchestrator.runSingle(
                makeRequest("single", "단일 작업"));

        assertThat(result.status()).isEqualTo(SubagentResult.Status.COMPLETED);
        assertThat(result.output()).contains("단일 결과");
    }

    @Test
    void runParallel_twoAgents_mergesResults() {
        // 병렬 실행 시 thenAnswer로 thread-safe 응답
        when(orchestratorEngine.chat(any()))
                .thenAnswer(inv -> new ChatResponse("r", "m", "s",
                        List.of(new ContentBlock.Text("결과")),
                        List.of(), new TokenUsage(100, 200), 0.01));

        List<SubagentRequest> requests = List.of(
                makeRequest("agent-1", "작업A"),
                makeRequest("agent-2", "작업B"));

        AgentOrchestrator.OrchestratedResult result = orchestrator.runParallel(requests);

        assertThat(result.results()).hasSize(2);
        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.failCount()).isEqualTo(0);
        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.mergedOutput()).contains("결과");
        assertThat(result.totalUsage().inputTokens()).isEqualTo(200);
        assertThat(result.totalUsage().outputTokens()).isEqualTo(400);
    }

    @Test
    void runSequential_threeAgents_allComplete() {
        when(orchestratorEngine.chat(any()))
                .thenReturn(new ChatResponse("r1", "m", "s1",
                        List.of(new ContentBlock.Text("순차1")),
                        List.of(), new TokenUsage(10, 20), 0.001))
                .thenReturn(new ChatResponse("r2", "m", "s2",
                        List.of(new ContentBlock.Text("순차2")),
                        List.of(), new TokenUsage(20, 30), 0.002))
                .thenReturn(new ChatResponse("r3", "m", "s3",
                        List.of(new ContentBlock.Text("순차3")),
                        List.of(), new TokenUsage(30, 40), 0.003));

        List<SubagentRequest> requests = List.of(
                makeRequest("seq-1", "작업1"),
                makeRequest("seq-2", "작업2"),
                makeRequest("seq-3", "작업3"));

        AgentOrchestrator.OrchestratedResult result = orchestrator.runSequential(requests);

        assertThat(result.results()).hasSize(3);
        assertThat(result.successCount()).isEqualTo(3);
        assertThat(result.totalUsage().inputTokens()).isEqualTo(60);
    }

    @Test
    void runSequential_oneFails_countsCorrectly() {
        // 순차 실행으로 실패 시나리오 검증 (thenReturn→thenThrow 순서 보장)
        when(orchestratorEngine.chat(any()))
                .thenReturn(new ChatResponse("r1", "m", "s1",
                        List.of(new ContentBlock.Text("성공")),
                        List.of(), new TokenUsage(50, 100), 0.005))
                .thenThrow(new RuntimeException("LLM 실패"));

        List<SubagentRequest> requests = List.of(
                makeRequest("ok-agent", "성공 작업"),
                makeRequest("fail-agent", "실패 작업"));

        AgentOrchestrator.OrchestratedResult result = orchestrator.runSequential(requests);

        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.failCount()).isEqualTo(1);
        assertThat(result.allSucceeded()).isFalse();
    }
}
