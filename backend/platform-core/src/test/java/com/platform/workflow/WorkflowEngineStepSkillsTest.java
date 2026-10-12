package com.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.monitoring.PlatformMetrics;
import com.platform.repository.PendingApprovalRepository;
import com.platform.repository.WorkflowRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.skill.PinnedSkill;
import com.platform.skill.ResolvedSkill;
import com.platform.skill.SkillNotFoundException;
import com.platform.skill.SkillScope;
import com.platform.skill.StepSkillPins;
import com.platform.skill.StepSkillService;
import com.platform.workflow.event.WorkflowEventPublisher;
import com.platform.workflow.event.WorkflowRunEventRecorder;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-147 단계 시작 때 스킬 고정과 STEP_START payload. TC-SKB-022, 023 의 기록 부분.
 */
class WorkflowEngineStepSkillsTest {

    private WorkflowRunEventRecorder recorder;
    private StepSkillService stepSkills;
    private WorkflowEngine engine;
    private final UUID runId = UUID.randomUUID();
    private final WorkflowStep step = new WorkflowStep("s1", "s1", WorkflowStep.StepType.AGENT_CALL,
            Map.of("prompt", "p", "skills", List.of(Map.of("name", "demo"))), List.of(), null, null, null);
    private final StepContext context = new StepContext(runId.toString(), "wf", "sess", Map.of(), Map.of());

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @BeforeEach
    void setUp() {
        recorder = mock(WorkflowRunEventRecorder.class);
        stepSkills = mock(StepSkillService.class);
        engine = new WorkflowEngine(mock(WorkflowRepository.class), mock(WorkflowRunRepository.class),
                mock(PendingApprovalRepository.class), new ObjectMapper(), List.of(), mock(PlatformMetrics.class),
                mock(WorkflowEventPublisher.class), provider(recorder), null, null, null, null, null,
                provider(stepSkills));
    }

    @Test
    void pinned_skills_go_to_step_start_and_only_to_this_step_context() {
        StepSkillPins pins = new StepSkillPins("s1", List.of(new PinnedSkill(
                new ResolvedSkill("demo", SkillScope.PLATFORM, 3, "d", UUID.randomUUID()), List.of())), List.of());
        when(stepSkills.pin(step)).thenReturn(pins);

        StepContext stepContext = engine.startStep(runId, step, context);

        verify(recorder).stepStart(runId, "s1", "AGENT_CALL",
                List.of(Map.of("name", "demo", "scope", "platform", "version", 3)));
        assertThat(stepContext.skillPins()).isSameAs(pins);
        assertThat(context.skillPins()).isNull();
    }

    @Test
    void pin_failure_still_records_step_start_and_fails_the_step() {
        when(stepSkills.pin(step)).thenThrow(new SkillNotFoundException("BIZ-120: 활성 스킬 묶음이 없습니다: demo"));

        assertThatThrownBy(() -> engine.startStep(runId, step, context)).hasMessageContaining("BIZ-120");
        verify(recorder).stepStart(runId, "s1", "AGENT_CALL");
    }
}
