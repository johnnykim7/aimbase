package com.platform.agent;

import com.platform.hook.HookDispatcher;
import com.platform.hook.HookOutput;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.TokenUsage;
import com.platform.llm.model.UnifiedMessage;
import com.platform.orchestrator.ChatRequest;
import com.platform.orchestrator.ChatResponse;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.repository.SubagentRunRepository;
import com.platform.skill.PinnedSkill;
import com.platform.skill.ResolvedSkill;
import com.platform.skill.SkillScope;
import com.platform.skill.StepSkillPins;
import com.platform.skill.StepSkillService;
import com.platform.tool.SkillRef;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import com.platform.workflow.step.AgentCallStepExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CR-147 AGENT_CALL 스킬 지정이 실제 SubagentRunner 를 거쳐 ChatRequest 까지 가는지. TC-SKB-023.
 */
@ExtendWith(MockitoExtension.class)
class AgentCallStepExecutorSkillTest {

    @Mock private OrchestratorEngine orchestratorEngine;
    @Mock private SubagentRunRepository subagentRunRepository;
    @Mock private WorktreeManager worktreeManager;
    @Mock private HookDispatcher hookDispatcher;
    @Mock private SubagentLifecycleManager lifecycleManager;
    @Mock private AgentTypeRegistry agentTypeRegistry;
    @Mock private PlanService planService;
    @Mock private ConnectionAdapterFactory connectionAdapterFactory;
    @Mock private StepSkillService stepSkills;
    @Mock private ObjectProvider<StepSkillService> stepSkillsProvider;

    private AgentCallStepExecutor executor;

    private final ResolvedSkill demoV1 = new ResolvedSkill("demo", SkillScope.TENANT, 1, "Writes proposals", UUID.randomUUID());
    private final StepContext context = new StepContext("run-1", "wf-1", "sess-1", Map.of(), Map.of());

    @BeforeEach
    void setUp() {
        lenient().when(hookDispatcher.dispatch(any(), any())).thenReturn(HookOutput.PASSTHROUGH);
        lenient().when(subagentRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(agentTypeRegistry.getConfig(any()))
                .thenReturn(new AgentTypeRegistry.AgentTypeConfig(AgentType.GENERAL, "general prompt", null, false));
        when(stepSkillsProvider.getIfAvailable()).thenReturn(stepSkills);
        SubagentRunner runner = new SubagentRunner(orchestratorEngine, subagentRunRepository,
                worktreeManager, hookDispatcher, lifecycleManager, agentTypeRegistry, planService, connectionAdapterFactory,
                new ActiveCliWorkerRegistry());
        executor = new AgentCallStepExecutor(new AgentOrchestrator(runner), stepSkillsProvider);
        when(orchestratorEngine.chat(any())).thenReturn(new ChatResponse("resp", "claude", "child",
                List.of(new ContentBlock.Text("done")), List.of(), new TokenUsage(1, 1), 0.0));
    }

    private static String text(UnifiedMessage m) {
        return ((ContentBlock.Text) m.content().get(0)).text();
    }

    @Test
    void TC_SKB_023_listing_message_skill_tools_and_pinned_version_reach_the_chat() {
        WorkflowStep step = new WorkflowStep("s1", "agent", WorkflowStep.StepType.AGENT_CALL,
                Map.of("prompt", "write it", "tools", List.of("file_write"),
                        "skills", List.of(Map.of("name", "demo", "version", 1))),
                List.of(), null, null, null);
        List<PinnedSkill> pinned = List.of(new PinnedSkill(demoV1, List.of()));
        when(stepSkills.pinsFor(eq(step), any())).thenReturn(new StepSkillPins("s1", pinned, List.of()));
        when(stepSkills.agentSystemMessage(pinned)).thenReturn("Skills:\n- demo: Writes proposals");

        executor.execute(step, context);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(orchestratorEngine).chat(captor.capture());
        ChatRequest chat = captor.getValue();
        List<UnifiedMessage> messages = chat.messages();
        UnifiedMessage last = messages.get(messages.size() - 1);
        UnifiedMessage listing = messages.get(messages.size() - 2);
        assertThat(last.role()).isEqualTo(UnifiedMessage.Role.USER);
        assertThat(listing.role()).isEqualTo(UnifiedMessage.Role.SYSTEM);
        assertThat(text(listing)).isEqualTo("Skills:\n- demo: Writes proposals");
        assertThat(chat.toolFilter().allowedTools())
                .containsExactly("file_write", "skill_open", "skill_read", "skill_run");
        assertThat(chat.skillSet()).containsExactly(new SkillRef("demo", "tenant", 1));
    }

    @Test
    void step_without_skills_is_unchanged() {
        WorkflowStep step = new WorkflowStep("s1", "agent", WorkflowStep.StepType.AGENT_CALL,
                Map.of("prompt", "write it", "tools", List.of("file_write")), List.of(), null, null, null);
        when(stepSkills.pinsFor(eq(step), any())).thenReturn(StepSkillPins.none("s1"));

        executor.execute(step, context);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(orchestratorEngine).chat(captor.capture());
        assertThat(captor.getValue().toolFilter().allowedTools()).containsExactly("file_write");
        assertThat(captor.getValue().skillSet()).isNullOrEmpty();
        assertThat(captor.getValue().messages()).noneMatch(m -> text(m).startsWith("Skills:"));
        verify(stepSkills, never()).agentSystemMessage(any());
    }

    @Test
    void multi_agent_uses_each_agents_own_skills() {
        WorkflowStep step = new WorkflowStep("s1", "agents", WorkflowStep.StepType.AGENT_CALL,
                Map.of("execution", "sequential", "agents", List.of(
                        Map.of("description", "a", "prompt", "with skill", "skills", List.of(Map.of("name", "demo"))),
                        Map.of("description", "b", "prompt", "without"))),
                List.of(), null, null, null);
        List<PinnedSkill> pinned = List.of(new PinnedSkill(demoV1, List.of()));
        when(stepSkills.pinsFor(eq(step), any()))
                .thenReturn(new StepSkillPins("s1", List.of(), List.of(pinned, List.of())));
        when(stepSkills.agentSystemMessage(pinned)).thenReturn("Skills:\n- demo: Writes proposals");

        executor.execute(step, context);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(orchestratorEngine, times(2)).chat(captor.capture());
        assertThat(captor.getAllValues().get(0).skillSet()).containsExactly(new SkillRef("demo", "tenant", 1));
        assertThat(captor.getAllValues().get(1).skillSet()).isNullOrEmpty();
    }
}
