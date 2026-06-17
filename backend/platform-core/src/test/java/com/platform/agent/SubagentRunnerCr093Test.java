package com.platform.agent;

import com.platform.domain.PlanEntity;
import com.platform.domain.SubagentRunEntity;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookOutput;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.TokenUsage;
import com.platform.orchestrator.ChatRequest;
import com.platform.orchestrator.ChatResponse;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.repository.SubagentRunRepository;
import com.platform.tool.ToolFilterContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CR-093: SubagentRunner 갭 정공 해소 검증.
 * - P2: ToolFilterContext 화이트리스트 전파 (PLAN/EXPLORE/GUIDE/VERIFICATION)
 * - P4: agentType=PLAN 자동 진입 (PlanService.createPlan 호출 확인)
 * - P5: depth 카운팅 + max-depth=3 차단 (BIZ-109)
 */
@ExtendWith(MockitoExtension.class)
class SubagentRunnerCr093Test {

    @Mock private OrchestratorEngine orchestratorEngine;
    @Mock private SubagentRunRepository subagentRunRepository;
    @Mock private WorktreeManager worktreeManager;
    @Mock private HookDispatcher hookDispatcher;
    @Mock private SubagentLifecycleManager lifecycleManager;
    @Mock private AgentTypeRegistry agentTypeRegistry;
    @Mock private PlanService planService;
    @Mock private ConnectionAdapterFactory connectionAdapterFactory;

    private SubagentRunner runner;

    @BeforeEach
    void setUp() {
        runner = new SubagentRunner(orchestratorEngine, subagentRunRepository,
                worktreeManager, hookDispatcher, lifecycleManager, agentTypeRegistry, planService, connectionAdapterFactory);
        lenient().when(hookDispatcher.dispatch(any(), any())).thenReturn(HookOutput.PASSTHROUGH);
        lenient().when(subagentRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ChatResponse simpleResponse() {
        return new ChatResponse(
                "resp-x", "claude", "child",
                List.of(new ContentBlock.Text("ok")),
                List.of(), new TokenUsage(10, 20), 0.0);
    }

    // ── P2: 화이트리스트 전파 ──

    @Test
    void explore_agent_propagates_whitelist_and_readOnly() {
        // EXPLORE 타입: allowedTools = {read, grep, send_message, task_output}, readOnly=true
        when(agentTypeRegistry.getConfig(AgentType.EXPLORE))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig(
                        AgentType.EXPLORE, "explore prompt",
                        new java.util.LinkedHashSet<>(Set.of("read", "grep", "send_message", "task_output")),
                        true));
        when(orchestratorEngine.chat(any())).thenReturn(simpleResponse());

        SubagentRequest req = new SubagentRequest(
                "exp", "find X", null, null,
                SubagentRequest.IsolationMode.NONE, false, 30_000,
                Map.of(), "parent-1", AgentType.EXPLORE);

        runner.run(req);

        ArgumentCaptor<ChatRequest> chatCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(orchestratorEngine).chat(chatCaptor.capture());
        ToolFilterContext filter = chatCaptor.getValue().toolFilter();
        assertThat(filter).isNotNull();
        assertThat(filter.allowedTools()).containsExactlyInAnyOrder("read", "grep", "send_message", "task_output");
        assertThat(filter.readOnlyMode()).isTrue();
    }

    @Test
    void general_agent_no_filter_applied() {
        // GENERAL: allowedTools = null → 필터 없음
        when(agentTypeRegistry.getConfig(AgentType.GENERAL))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig(
                        AgentType.GENERAL, "general prompt", null, false));
        when(orchestratorEngine.chat(any())).thenReturn(simpleResponse());

        SubagentRequest req = new SubagentRequest(
                "g", "do", null, null,
                SubagentRequest.IsolationMode.NONE, false, 30_000,
                Map.of(), "parent-2", AgentType.GENERAL);

        runner.run(req);

        ArgumentCaptor<ChatRequest> chatCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(orchestratorEngine).chat(chatCaptor.capture());
        assertThat(chatCaptor.getValue().toolFilter()).isNull();
    }

    // ── P4: PLAN 자동 진입 ──

    @Test
    void plan_agent_triggers_createPlan_when_no_active_plan() {
        when(agentTypeRegistry.getConfig(AgentType.PLAN))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig(
                        AgentType.PLAN, "plan prompt",
                        new java.util.LinkedHashSet<>(Set.of("read", "grep")), true));
        when(planService.hasActivePlan("parent-3")).thenReturn(false);
        PlanEntity plan = new PlanEntity();
        plan.setId(UUID.randomUUID());
        when(planService.createPlan(eq("parent-3"), any(), any(), any())).thenReturn(plan);
        when(orchestratorEngine.chat(any())).thenReturn(simpleResponse());

        SubagentRequest req = new SubagentRequest(
                "design feature X", "Plan it", null, null,
                SubagentRequest.IsolationMode.NONE, false, 30_000,
                Map.of(), "parent-3", AgentType.PLAN);

        runner.run(req);

        verify(planService).createPlan(eq("parent-3"), eq("design feature X"), any(), any());
    }

    @Test
    void plan_agent_skips_createPlan_when_already_active() {
        when(agentTypeRegistry.getConfig(AgentType.PLAN))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig(
                        AgentType.PLAN, "plan prompt", null, true));
        when(planService.hasActivePlan("parent-4")).thenReturn(true);
        when(orchestratorEngine.chat(any())).thenReturn(simpleResponse());

        SubagentRequest req = new SubagentRequest(
                "design", "Plan it", null, null,
                SubagentRequest.IsolationMode.NONE, false, 30_000,
                Map.of(), "parent-4", AgentType.PLAN);

        runner.run(req);

        verify(planService, never()).createPlan(any(), any(), any(), any());
    }

    @Test
    void non_plan_agent_never_triggers_planService() {
        when(agentTypeRegistry.getConfig(AgentType.EXPLORE))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig(
                        AgentType.EXPLORE, "ep", null, true));
        when(orchestratorEngine.chat(any())).thenReturn(simpleResponse());

        SubagentRequest req = new SubagentRequest(
                "exp", "find", null, null,
                SubagentRequest.IsolationMode.NONE, false, 30_000,
                Map.of(), "parent-5", AgentType.EXPLORE);

        runner.run(req);

        verify(planService, never()).hasActivePlan(any());
        verify(planService, never()).createPlan(any(), any(), any(), any());
    }

    // ── P5: depth 카운팅 + 차단 ──

    @Test
    void root_call_has_depth_zero() {
        // parentSessionId 가 자식 세션이 아닐 때(=root) depth=0
        when(subagentRunRepository.findFirstByChildSessionId(any())).thenReturn(Optional.empty());
        assertThat(runner.resolveDepth("normal-session")).isZero();
        assertThat(runner.resolveDepth(null)).isZero();
        assertThat(runner.resolveDepth("")).isZero();
    }

    @Test
    void child_call_inherits_parent_depth_plus_one() {
        SubagentRunEntity parent = new SubagentRunEntity();
        parent.setDepth(1);
        when(subagentRunRepository.findFirstByChildSessionId("subagent-xyz"))
                .thenReturn(Optional.of(parent));

        assertThat(runner.resolveDepth("subagent-xyz")).isEqualTo(2);
    }

    @Test
    void depth_exceeded_returns_failed_without_save_or_hook() {
        SubagentRunEntity parent = new SubagentRunEntity();
        parent.setDepth(SubagentRunner.MAX_SUBAGENT_DEPTH);  // 자식이 +1 → 초과
        when(subagentRunRepository.findFirstByChildSessionId("subagent-deep"))
                .thenReturn(Optional.of(parent));

        SubagentRequest req = new SubagentRequest(
                "deep", "go deeper", null, null,
                SubagentRequest.IsolationMode.NONE, false, 30_000,
                Map.of(), "subagent-deep", AgentType.GENERAL);

        SubagentResult result = runner.run(req);

        assertThat(result.status()).isEqualTo(SubagentResult.Status.FAILED);
        assertThat(result.error()).contains("depth limit exceeded");
        verify(subagentRunRepository, never()).save(any());
        verify(orchestratorEngine, never()).chat(any());
        verify(hookDispatcher, never()).dispatch(any(), any());
    }
}
