package com.platform.workflow.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.llm.model.*;
import com.platform.llm.router.ModelRouter;
import com.platform.schema.SchemaValidator;
import com.platform.service.PromptTemplateService;
import com.platform.skill.PinnedSkill;
import com.platform.skill.ResolvedSkill;
import com.platform.skill.SkillScope;
import com.platform.skill.StepSkillPins;
import com.platform.skill.StepSkillService;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CR-147 LLM_CALL 이 단계 지정 스킬의 안내서와 파일을 system 뒤에 붙이는지. TC-SKB-022.
 */
class LlmCallStepExecutorSkillTest {

    private LLMAdapter adapter;
    private StepSkillService stepSkills;
    private LlmCallStepExecutor executor;
    private final List<PinnedSkill> pinned = List.of(new PinnedSkill(
            new ResolvedSkill("demo", SkillScope.TENANT, 2, "d", UUID.randomUUID()), List.of("references/a.md")));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ConnectionAdapterFactory factory = mock(ConnectionAdapterFactory.class);
        adapter = mock(LLMAdapter.class);
        when(factory.getAdapter("conn-1")).thenReturn(adapter);
        when(factory.resolveModel("conn-1", "auto")).thenReturn("anthropic/claude-sonnet-4-5");
        when(adapter.chat(any(LLMRequest.class))).thenReturn(CompletableFuture.completedFuture(
                new LLMResponse("m", "anthropic/claude-sonnet-4-5", List.of(new ContentBlock.Text("ok")),
                        List.of(), new TokenUsage(10, 10), LLMResponse.FinishReason.END, 10L, 0.0)));
        stepSkills = mock(StepSkillService.class);
        ObjectProvider<StepSkillService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(stepSkills);
        executor = new LlmCallStepExecutor(mock(ModelRouter.class), factory, mock(PromptTemplateService.class),
                null, null, new SchemaValidator(new ObjectMapper()), provider);
    }

    private WorkflowStep step() {
        Map<String, Object> config = new HashMap<>();
        config.put("connection_id", "conn-1");
        config.put("system", "You review bids.");
        config.put("prompt", "Review {{input.title}}");
        config.put("skills", List.of(Map.of("name", "demo", "files", List.of("references/a.md"))));
        return new WorkflowStep("s1", "llm", WorkflowStep.StepType.LLM_CALL, config, List.of(), null, null, null);
    }

    private final StepContext context = new StepContext("run-1", "wf-1", "sess-1", Map.of("title", "T"), Map.of());

    @Test
    void TC_SKB_022_skill_text_follows_the_step_system() {
        WorkflowStep step = step();
        when(stepSkills.pinsFor(eq(step), any())).thenReturn(new StepSkillPins("s1", pinned, List.of()));
        when(stepSkills.inlineText(pinned)).thenReturn("<skill name=\"demo\">rules</skill>");

        executor.execute(step, context);

        ArgumentCaptor<LLMRequest> captor = ArgumentCaptor.forClass(LLMRequest.class);
        verify(adapter).chat(captor.capture());
        UnifiedMessage system = captor.getValue().messages().get(0);
        assertThat(system.role()).isEqualTo(UnifiedMessage.Role.SYSTEM);
        assertThat(((ContentBlock.Text) system.content().get(0)).text())
                .isEqualTo("You review bids.\n\n<skill name=\"demo\">rules</skill>");
    }

    @Test
    void TC_SKB_022_over_limit_fails_before_calling_the_model() {
        WorkflowStep step = step();
        when(stepSkills.pinsFor(eq(step), any())).thenReturn(new StepSkillPins("s1", pinned, List.of()));
        when(stepSkills.inlineText(pinned)).thenThrow(new IllegalStateException("BIZ-121: 합계가 300000 bytes"));

        assertThatThrownBy(() -> executor.execute(step, context)).hasMessageContaining("BIZ-121");
        verify(adapter, never()).chat(any());
    }
}
