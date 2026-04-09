package com.platform.agent;

import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.TokenUsage;
import com.platform.orchestrator.ChatResponse;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookOutput;
import com.platform.repository.SubagentRunRepository;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import com.platform.workflow.step.AgentCallStepExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CR-030 PRD-210: AgentCallStepExecutor 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
class AgentCallStepExecutorTest {

    @Mock private OrchestratorEngine orchestratorEngine;
    @Mock private SubagentRunRepository subagentRunRepository;
    @Mock private WorktreeManager worktreeManager;
    @Mock private HookDispatcher hookDispatcher;
    @Mock private SubagentLifecycleManager lifecycleManager;
    @Mock private AgentTypeRegistry agentTypeRegistry;

    private AgentCallStepExecutor executor;

    @BeforeEach
    void setUp() {
        lenient().when(hookDispatcher.dispatch(any(), any())).thenReturn(HookOutput.PASSTHROUGH);
        lenient().when(subagentRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SubagentRunner runner = new SubagentRunner(orchestratorEngine, subagentRunRepository,
                worktreeManager, hookDispatcher, lifecycleManager, agentTypeRegistry);
        AgentOrchestrator orchestrator = new AgentOrchestrator(runner);
        executor = new AgentCallStepExecutor(orchestrator);
    }

    @Test
    void supports_returnsAgentCall() {
        assertThat(executor.supports()).isEqualTo(WorkflowStep.StepType.AGENT_CALL);
    }

    @Test
    void singleAgent_success() {
        mockChat("에이전트 결과");

        WorkflowStep step = new WorkflowStep("s1", "agent step",
                WorkflowStep.StepType.AGENT_CALL,
                Map.of("description", "테스트 에이전트", "prompt", "작업 수행"),
                List.of(), null, null, null);
        StepContext context = new StepContext("run-1", "wf-1", "sess-1",
                Map.of(), Map.of());

        Map<String, Object> result = executor.execute(step, context);

        assertThat(result.get("output")).isEqualTo("에이전트 결과");
        assertThat(result.get("status")).isEqualTo("COMPLETED");
        assertThat(result.get("exit_code")).isEqualTo(0);
    }

    @Test
    void singleAgent_withVariableSubstitution() {
        mockChat("분석 완료");

        WorkflowStep step = new WorkflowStep("s2", "var step",
                WorkflowStep.StepType.AGENT_CALL,
                Map.of("description", "분석 에이전트", "prompt", "{{input.code}}를 분석해"),
                List.of(), null, null, null);
        StepContext context = new StepContext("run-1", "wf-1", "sess-1",
                Map.of("code", "console.log('hello')"), Map.of());

        Map<String, Object> result = executor.execute(step, context);

        assertThat(result.get("output")).isEqualTo("분석 완료");
    }

    @Test
    void multiAgent_parallel() {
        // 병렬 실행 시 순서 무관하게 모두 동일 응답 반환
        when(orchestratorEngine.chat(any()))
                .thenAnswer(inv -> makeChatResponse("결과", 100, 200));

        WorkflowStep step = new WorkflowStep("s3", "multi step",
                WorkflowStep.StepType.AGENT_CALL,
                Map.of("agents", List.of(
                                Map.of("description", "agent-1", "prompt", "작업A"),
                                Map.of("description", "agent-2", "prompt", "작업B")),
                        "execution", "parallel"),
                List.of(), null, null, null);
        StepContext context = new StepContext("run-1", "wf-1", "sess-1",
                Map.of(), Map.of());

        Map<String, Object> result = executor.execute(step, context);

        assertThat(result.get("agent_count")).isEqualTo(2);
        assertThat(result.get("success_count")).isEqualTo(2);
        assertThat(result.get("all_succeeded")).isEqualTo(true);
        assertThat(result.get("execution")).isEqualTo("parallel");
        assertThat((List<?>) result.get("agents")).hasSize(2);
    }

    @Test
    void multiAgent_sequential() {
        when(orchestratorEngine.chat(any()))
                .thenReturn(makeChatResponse("순차1", 10, 20))
                .thenReturn(makeChatResponse("순차2", 20, 30));

        WorkflowStep step = new WorkflowStep("s4", "seq step",
                WorkflowStep.StepType.AGENT_CALL,
                Map.of("agents", List.of(
                                Map.of("description", "seq-1", "prompt", "순차작업1"),
                                Map.of("description", "seq-2", "prompt", "순차작업2")),
                        "execution", "sequential"),
                List.of(), null, null, null);
        StepContext context = new StepContext("run-1", "wf-1", "sess-1",
                Map.of(), Map.of());

        Map<String, Object> result = executor.execute(step, context);

        assertThat(result.get("execution")).isEqualTo("sequential");
        assertThat(result.get("success_count")).isEqualTo(2);
    }

    @Test
    void singleAgent_missingPrompt_throws() {
        WorkflowStep step = new WorkflowStep("s5", "no prompt",
                WorkflowStep.StepType.AGENT_CALL,
                Map.of("description", "에이전트"),
                List.of(), null, null, null);
        StepContext context = new StepContext("run-1", "wf-1", "sess-1",
                Map.of(), Map.of());

        assertThatThrownBy(() -> executor.execute(step, context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prompt");
    }

    @Test
    void singleAgent_withIsolation() {
        WorktreeContext wCtx = new WorktreeContext("/tmp/wt", "subagent/x", "abc");
        when(worktreeManager.create(any())).thenReturn(wCtx);
        when(worktreeManager.hasChanges(wCtx)).thenReturn(true);
        mockChat("격리 결과");

        WorkflowStep step = new WorkflowStep("s6", "isolated",
                WorkflowStep.StepType.AGENT_CALL,
                Map.of("description", "격리 에이전트", "prompt", "격리 작업",
                        "isolation", "WORKTREE"),
                List.of(), null, null, null);
        StepContext context = new StepContext("run-1", "wf-1", "sess-1",
                Map.of(), Map.of());

        Map<String, Object> result = executor.execute(step, context);

        assertThat(result.get("worktree_path")).isEqualTo("/tmp/wt");
        assertThat(result.get("branch_name")).isEqualTo("subagent/x");
    }

    // ── Helper ──

    private void mockChat(String text) {
        when(orchestratorEngine.chat(any())).thenReturn(makeChatResponse(text, 50, 100));
    }

    private ChatResponse makeChatResponse(String text, int inputTokens, int outputTokens) {
        return new ChatResponse("resp", "claude-sonnet", "child",
                List.of(new ContentBlock.Text(text)),
                List.of(), new TokenUsage(inputTokens, outputTokens), 0.005);
    }
}
