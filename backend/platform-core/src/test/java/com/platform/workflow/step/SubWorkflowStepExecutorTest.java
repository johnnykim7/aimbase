package com.platform.workflow.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.master.PlatformWorkflowEntity;
import com.platform.repository.master.PlatformWorkflowRepository;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-097 SubWorkflowStepExecutor 단위 테스트.
 *
 * <p>플랫폼 공용 워크플로우를 fake {@link PlatformWorkflowEntity}(steps JSON)로 구성하고
 * 하위 step executor 를 fake StepExecutor 로 주입(ApplicationContext.getBeansOfType mock)하여
 * config 검증 / 비활성 방어 / 입력 변수치환 / 서브스텝 순차실행 / 출력 반환만 검증한다.
 * 실제 LLM/Tool 호출은 없다.
 */
class SubWorkflowStepExecutorTest {

    private PlatformWorkflowRepository platformWorkflowRepository;
    private ApplicationContext applicationContext;
    private SubWorkflowStepExecutor executor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        platformWorkflowRepository = mock(PlatformWorkflowRepository.class);
        applicationContext = mock(ApplicationContext.class);
        executor = new SubWorkflowStepExecutor(platformWorkflowRepository, objectMapper, applicationContext);
    }

    @Test
    @DisplayName("supports() — SUB_WORKFLOW")
    void supportsSubWorkflow() {
        assertThat(executor.supports()).isEqualTo(WorkflowStep.StepType.SUB_WORKFLOW);
    }

    // ─── fake 하위 step executor ─────────────────────────────────────────────

    /** TOOL_CALL 로 가장한 fake — {{input.*}} resolve 한 값을 output 에 담고 호출 순서 기록. */
    static class FakeToolExecutor implements StepExecutor {
        final ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();

        @Override public WorkflowStep.StepType supports() { return WorkflowStep.StepType.TOOL_CALL; }

        @Override
        public Map<String, Object> execute(WorkflowStep step, StepContext context) {
            // 서브 워크플로우 입력은 subContext 의 inputData 로 들어온다 ({{input.*}})
            String echoed = context.resolve((String) step.config().getOrDefault("echo", ""));
            seen.add(step.id());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("output", echoed);
            r.put("step", step.id());
            return r;
        }
    }

    private FakeToolExecutor wireToolExecutor() {
        FakeToolExecutor fake = new FakeToolExecutor();
        when(applicationContext.getBeansOfType(StepExecutor.class))
                .thenReturn(Map.of("fake", fake));
        return fake;
    }

    // ─── fake 플랫폼 워크플로우 ───────────────────────────────────────────────

    private PlatformWorkflowEntity platformWf(String id, boolean active, List<Map<String, Object>> steps) {
        PlatformWorkflowEntity e = new PlatformWorkflowEntity();
        e.setId(id);
        e.setName("Platform " + id);
        e.setActive(active);
        e.setSteps(steps);
        return e;
    }

    /** TOOL_CALL 서브스텝 1개를 가진 steps JSON. echo 는 {{input.*}} 변수 참조. */
    private Map<String, Object> toolStep(String id, String echo, List<String> dependsOn) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id);
        step.put("name", id);
        step.put("type", "TOOL_CALL");
        step.put("config", Map.of("echo", echo));
        if (dependsOn != null) step.put("dependsOn", dependsOn);
        return step;
    }

    private WorkflowStep subWorkflowStep(Map<String, Object> config) {
        return new WorkflowStep("sw", "Sub", WorkflowStep.StepType.SUB_WORKFLOW,
                config, List.of(), null, null, null);
    }

    private StepContext parentCtx(Map<String, Object> input) {
        return new StepContext("run-1", "parent-wf", "sess-1", input, new LinkedHashMap<>());
    }

    // ═══════════════════════════════════════════════
    // config 방어
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("workflow_id 누락 — IllegalArgumentException")
    void missingWorkflowIdFails() {
        StepContext ctx = parentCtx(new LinkedHashMap<>());
        assertThatThrownBy(() -> executor.execute(subWorkflowStep(new LinkedHashMap<>()), ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflow_id");
    }

    @Test
    @DisplayName("workflow_id 공백 — IllegalArgumentException")
    void blankWorkflowIdFails() {
        StepContext ctx = parentCtx(new LinkedHashMap<>());
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
        StepContext ctx = parentCtx(new LinkedHashMap<>());
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
                .thenReturn(Optional.of(platformWf("inactive", false,
                        List.of(toolStep("s1", "x", null)))));
        StepContext ctx = parentCtx(new LinkedHashMap<>());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("workflow_id", "inactive");

        assertThatThrownBy(() -> executor.execute(subWorkflowStep(config), ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not active");
    }

    // ═══════════════════════════════════════════════
    // steps 없음
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("steps 없는 워크플로우 — 빈 output + sub_workflow_id 반환")
    void emptyStepsReturnsEmptyOutput() {
        when(platformWorkflowRepository.findById("empty"))
                .thenReturn(Optional.of(platformWf("empty", true, List.of())));
        StepContext ctx = parentCtx(new LinkedHashMap<>());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("workflow_id", "empty");

        Map<String, Object> out = executor.execute(subWorkflowStep(config), ctx);

        assertThat(out.get("output")).isEqualTo("");
        assertThat(out.get("sub_workflow_id")).isEqualTo("empty");
    }

    // ═══════════════════════════════════════════════
    // 정상 실행 — 입력 변수치환 + 순차실행 + 출력반환
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("input 변수치환 — 부모 컨텍스트 값이 서브 워크플로우 input 으로 전달")
    void inputVariablesResolvedFromParent() {
        FakeToolExecutor fake = wireToolExecutor();
        // 서브스텝 1개: echo = {{input.zip_path}} (서브 워크플로우 입력 참조)
        when(platformWorkflowRepository.findById("file-analysis"))
                .thenReturn(Optional.of(platformWf("file-analysis", true,
                        List.of(toolStep("s1", "{{input.zip_path}}", null)))));

        // 부모 input.zip_path → SUB_WORKFLOW config.input.zip_path = {{input.zip_path}} → 치환
        Map<String, Object> parentInput = new LinkedHashMap<>();
        parentInput.put("zip_path", "/data/a.zip");
        StepContext ctx = parentCtx(parentInput);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("workflow_id", "file-analysis");
        Map<String, Object> subInput = new LinkedHashMap<>();
        subInput.put("zip_path", "{{input.zip_path}}");
        config.put("input", subInput);

        Map<String, Object> out = executor.execute(subWorkflowStep(config), ctx);

        assertThat(fake.seen).containsExactly("s1");
        assertThat(out.get("output")).isEqualTo("/data/a.zip");
        assertThat(out.get("sub_workflow_id")).isEqualTo("file-analysis");
    }

    @Test
    @DisplayName("다중 서브스텝 — dependsOn 위상정렬 순차실행, 마지막 output 반환")
    void multipleStepsExecutedInTopologicalOrder() {
        FakeToolExecutor fake = wireToolExecutor();
        // s1 → s2 (s2 dependsOn s1). echo 는 input 참조만 (단계 간 참조는 fake 단순화상 생략)
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(toolStep("s2", "{{input.v}}", List.of("s1")));
        steps.add(toolStep("s1", "first", null));
        when(platformWorkflowRepository.findById("multi"))
                .thenReturn(Optional.of(platformWf("multi", true, steps)));

        Map<String, Object> parentInput = new LinkedHashMap<>();
        parentInput.put("val", "LAST");
        StepContext ctx = parentCtx(parentInput);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("workflow_id", "multi");
        config.put("input", Map.of("v", "{{input.val}}"));

        Map<String, Object> out = executor.execute(subWorkflowStep(config), ctx);

        // 위상정렬: s1 먼저, s2 나중
        assertThat(fake.seen).containsExactly("s1", "s2");
        // 마지막 스텝(s2)의 output 반환 = "LAST"
        assertThat(out.get("output")).isEqualTo("LAST");
        assertThat(out.get("sub_workflow_id")).isEqualTo("multi");
    }

    @Test
    @DisplayName("input 미지정 — 빈 입력으로 실행")
    void noInputConfigRunsWithEmptyInput() {
        FakeToolExecutor fake = wireToolExecutor();
        when(platformWorkflowRepository.findById("no-input"))
                .thenReturn(Optional.of(platformWf("no-input", true,
                        List.of(toolStep("s1", "static", null)))));
        StepContext ctx = parentCtx(new LinkedHashMap<>());

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("workflow_id", "no-input");

        Map<String, Object> out = executor.execute(subWorkflowStep(config), ctx);

        assertThat(fake.seen).containsExactly("s1");
        assertThat(out.get("output")).isEqualTo("static");
    }

    @Test
    @DisplayName("서브스텝 타입 executor 미존재 — IllegalStateException")
    void missingExecutorForSubStepFails() {
        // ApplicationContext 가 빈 executor 맵 반환 → TOOL_CALL executor 없음
        when(applicationContext.getBeansOfType(StepExecutor.class)).thenReturn(Map.of());
        when(platformWorkflowRepository.findById("no-exec"))
                .thenReturn(Optional.of(platformWf("no-exec", true,
                        List.of(toolStep("s1", "x", null)))));
        StepContext ctx = parentCtx(new LinkedHashMap<>());

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("workflow_id", "no-exec");

        assertThatThrownBy(() -> executor.execute(subWorkflowStep(config), ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No executor for step type");
    }
}
