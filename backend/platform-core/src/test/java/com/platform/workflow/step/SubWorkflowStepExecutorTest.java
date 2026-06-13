package com.platform.workflow.step;

import com.platform.domain.master.PlatformWorkflowEntity;
import com.platform.repository.master.PlatformWorkflowRepository;
import com.platform.workflow.StepContext;
import com.platform.workflow.WorkflowEngine;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-065 SubWorkflowStepExecutor 단위 테스트.
 *
 * <p>인라인 실행을 자식 run 분리로 승격한 뒤의 동작 검증. executor 는 config 검증·변수치환만
 * 직접 책임지고, 실제 실행은 {@link WorkflowEngine#executeSubWorkflowSync} 로 위임한다.
 * 따라서 본 테스트는 (1) config 방어 (2) active 방어 (3) 부모 컨텍스트 → 엔진 위임 인자
 * 정확성 (4) 엔진 반환값 그대로 전파를 검증한다. 실제 서브스텝 실행은 엔진 테스트 책임.
 */
class SubWorkflowStepExecutorTest {

    private PlatformWorkflowRepository platformWorkflowRepository;
    private ApplicationContext applicationContext;
    private WorkflowEngine workflowEngine;
    private SubWorkflowStepExecutor executor;

    @BeforeEach
    void setUp() {
        platformWorkflowRepository = mock(PlatformWorkflowRepository.class);
        applicationContext = mock(ApplicationContext.class);
        workflowEngine = mock(WorkflowEngine.class);
        when(applicationContext.getBean(WorkflowEngine.class)).thenReturn(workflowEngine);
        executor = new SubWorkflowStepExecutor(platformWorkflowRepository, applicationContext);
    }

    @Test
    @DisplayName("supports() — SUB_WORKFLOW")
    void supportsSubWorkflow() {
        assertThat(executor.supports()).isEqualTo(WorkflowStep.StepType.SUB_WORKFLOW);
    }

    // ─── fake 플랫폼 워크플로우 / 컨텍스트 ───────────────────────────────────

    private PlatformWorkflowEntity platformWf(String id, boolean active) {
        PlatformWorkflowEntity e = new PlatformWorkflowEntity();
        e.setId(id);
        e.setName("Platform " + id);
        e.setActive(active);
        e.setSteps(List.of());
        return e;
    }

    private WorkflowStep subWorkflowStep(Map<String, Object> config) {
        return new WorkflowStep("sw", "Sub", WorkflowStep.StepType.SUB_WORKFLOW,
                config, List.of(), null, null, null);
    }

    private StepContext parentCtx(String runId, Map<String, Object> input) {
        return new StepContext(runId, "parent-wf", "sess-1", input, new LinkedHashMap<>());
    }

    // ═══════════════════════════════════════════════
    // config 방어
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("workflow_id 누락 — IllegalArgumentException")
    void missingWorkflowIdFails() {
        StepContext ctx = parentCtx(UUID.randomUUID().toString(), new LinkedHashMap<>());
        assertThatThrownBy(() -> executor.execute(subWorkflowStep(new LinkedHashMap<>()), ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflow_id");
    }

    @Test
    @DisplayName("workflow_id 공백 — IllegalArgumentException")
    void blankWorkflowIdFails() {
        StepContext ctx = parentCtx(UUID.randomUUID().toString(), new LinkedHashMap<>());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("workflow_id", "  ");
        assertThatThrownBy(() -> executor.execute(subWorkflowStep(config), ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflow_id");
    }

    @Test
    @DisplayName("플랫폼 워크플로우 미존재 — IllegalArgumentException")
    void workflowNotFoundFails() {
        when(platformWorkflowRepository.findById("ghost")).thenReturn(Optional.empty());
        StepContext ctx = parentCtx(UUID.randomUUID().toString(), new LinkedHashMap<>());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("workflow_id", "ghost");

        assertThatThrownBy(() -> executor.execute(subWorkflowStep(config), ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Platform workflow not found");
    }

    @Test
    @DisplayName("비활성 플랫폼 워크플로우 — IllegalStateException")
    void inactiveWorkflowFails() {
        when(platformWorkflowRepository.findById("inactive"))
                .thenReturn(Optional.of(platformWf("inactive", false)));
        StepContext ctx = parentCtx(UUID.randomUUID().toString(), new LinkedHashMap<>());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("workflow_id", "inactive");

        assertThatThrownBy(() -> executor.execute(subWorkflowStep(config), ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not active");
    }

    // ═══════════════════════════════════════════════
    // 엔진 위임 — 자식 run 분리
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("정상 — parentRunId/parentStepId/sessionId/변수치환된 input 을 엔진에 위임")
    void delegatesToEngineWithResolvedArgs() {
        UUID parentRunId = UUID.randomUUID();
        when(platformWorkflowRepository.findById("file-analysis"))
                .thenReturn(Optional.of(platformWf("file-analysis", true)));

        Map<String, Object> engineResult = new LinkedHashMap<>();
        engineResult.put("output", "done");
        engineResult.put("sub_workflow_id", "file-analysis");
        engineResult.put("sub_workflow_run_id", UUID.randomUUID().toString());
        when(workflowEngine.executeSubWorkflowSync(any(), any(), any(), any(), any()))
                .thenReturn(engineResult);

        // 부모 input.zip_path → SUB_WORKFLOW config.input.zip_path = {{input.zip_path}} → 치환
        Map<String, Object> parentInput = new LinkedHashMap<>();
        parentInput.put("zip_path", "/data/a.zip");
        StepContext ctx = parentCtx(parentRunId.toString(), parentInput);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("workflow_id", "file-analysis");
        Map<String, Object> subInput = new LinkedHashMap<>();
        subInput.put("zip_path", "{{input.zip_path}}");
        config.put("input", subInput);

        Map<String, Object> out = executor.execute(subWorkflowStep(config), ctx);

        // 반환값은 엔진 결과 그대로 전파 (신규 sub_workflow_run_id 포함)
        assertThat(out).isEqualTo(engineResult);
        assertThat(out.get("sub_workflow_run_id")).isNotNull();

        // 위임 인자 검증
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputCap = ArgumentCaptor.forClass(Map.class);
        verify(workflowEngine).executeSubWorkflowSync(
                any(PlatformWorkflowEntity.class), inputCap.capture(),
                eq(parentRunId), eq("sw"), eq("sess-1"));
        // 변수치환 결과: {{input.zip_path}} → /data/a.zip
        assertThat(inputCap.getValue()).containsEntry("zip_path", "/data/a.zip");
    }

    @Test
    @DisplayName("input 미지정 — 빈 입력으로 엔진에 위임")
    void noInputConfigDelegatesEmptyInput() {
        UUID parentRunId = UUID.randomUUID();
        when(platformWorkflowRepository.findById("no-input"))
                .thenReturn(Optional.of(platformWf("no-input", true)));
        when(workflowEngine.executeSubWorkflowSync(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("output", "x"));
        StepContext ctx = parentCtx(parentRunId.toString(), new LinkedHashMap<>());

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("workflow_id", "no-input");

        executor.execute(subWorkflowStep(config), ctx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> inputCap = ArgumentCaptor.forClass(Map.class);
        verify(workflowEngine).executeSubWorkflowSync(
                any(), inputCap.capture(), eq(parentRunId), eq("sw"), eq("sess-1"));
        assertThat(inputCap.getValue()).isEmpty();
    }

    @Test
    @DisplayName("workflowRunId 가 UUID 아님(테스트 컨텍스트) — parentRunId=null 로 위임")
    void nonUuidRunIdDelegatesNullParent() {
        when(platformWorkflowRepository.findById("wf"))
                .thenReturn(Optional.of(platformWf("wf", true)));
        when(workflowEngine.executeSubWorkflowSync(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("output", ""));
        StepContext ctx = parentCtx("not-a-uuid", new LinkedHashMap<>());

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("workflow_id", "wf");

        executor.execute(subWorkflowStep(config), ctx);

        verify(workflowEngine).executeSubWorkflowSync(
                any(), any(), eq((UUID) null), eq("sw"), eq("sess-1"));
    }
}
