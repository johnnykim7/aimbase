package com.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.WorkflowEntity;
import com.platform.monitoring.PlatformMetrics;
import com.platform.repository.PendingApprovalRepository;
import com.platform.repository.WorkflowRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * CR-084 P3 — Cyclic 스케줄러 분기 두뇌 단위 테스트.
 *
 * <p>{@code executeCyclic} 의 while 루프 자체는 DB/이벤트 의존이 커서 IT 영역.
 * 여기서는 다음 노드 결정·entry step·step budget·graph_mode 판정 등
 * <b>순수 분기 로직</b>을 직접 검증한다. 이 4개가 cyclic 스케줄러의 라우팅 두뇌다.
 *
 * <p>DAG 하위호환(graph_mode=null/dag → cyclic 미진입)도 함께 고정한다.
 */
@DisplayName("WorkflowEngine — CR-084 P3 cyclic 분기 로직")
class WorkflowEngineCyclicTest {

    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        // 헬퍼 메서드는 주입 의존성을 사용하지 않으므로 mock 으로 충분.
        engine = new WorkflowEngine(
                mock(WorkflowRepository.class),
                mock(WorkflowRunRepository.class),
                mock(PendingApprovalRepository.class),
                new ObjectMapper(),
                List.of(),
                mock(PlatformMetrics.class),
                mock(com.platform.workflow.event.WorkflowEventPublisher.class));
    }

    private WorkflowEntity wf(String graphMode) {
        WorkflowEntity e = new WorkflowEntity();
        e.setId("wf1");
        e.setGraphMode(graphMode);
        return e;
    }

    private WorkflowStep step(String id, WorkflowStep.StepType type, List<String> dependsOn, String onSuccess) {
        return new WorkflowStep(id, id, type, Map.of(), dependsOn, onSuccess, null, null);
    }

    @Nested
    @DisplayName("isCyclicMode — graph_mode 판정 (하위호환)")
    class IsCyclicMode {
        @Test
        @DisplayName("null → DAG (cyclic 아님) — 기존 워크플로우 하위호환")
        void nullIsDag() {
            assertThat(engine.isCyclicMode(wf(null))).isFalse();
        }

        @Test
        @DisplayName("'dag' → cyclic 아님")
        void dagIsDag() {
            assertThat(engine.isCyclicMode(wf("dag"))).isFalse();
        }

        @Test
        @DisplayName("'cyclic' → cyclic (대소문자 무시)")
        void cyclicIsCyclic() {
            assertThat(engine.isCyclicMode(wf("cyclic"))).isTrue();
            assertThat(engine.isCyclicMode(wf("CYCLIC"))).isTrue();
        }
    }

    @Nested
    @DisplayName("resolveNextStep — 다음 노드 결정")
    class ResolveNextStep {
        @Test
        @DisplayName("ROUTER → 결과의 next_step 사용 (N-way)")
        void routerUsesNextStep() {
            WorkflowStep s = step("r", WorkflowStep.StepType.ROUTER, null, null);
            String next = engine.resolveNextStep(s, Map.of("next_step", "branch_b"));
            assertThat(next).isEqualTo("branch_b");
        }

        @Test
        @DisplayName("CONDITION → 결과의 next_step 사용")
        void conditionUsesNextStep() {
            WorkflowStep s = step("c", WorkflowStep.StepType.CONDITION, null, null);
            assertThat(engine.resolveNextStep(s, Map.of("next_step", "yes_step")))
                    .isEqualTo("yes_step");
        }

        @Test
        @DisplayName("일반 스텝(LLM_CALL) → onSuccess 를 그래프 엣지로 사용")
        void normalStepUsesOnSuccess() {
            WorkflowStep s = step("a", WorkflowStep.StepType.LLM_CALL, null, "a"); // 자기 자신 = cycle
            assertThat(engine.resolveNextStep(s, Map.of("output", "x"))).isEqualTo("a");
        }

        @Test
        @DisplayName("일반 스텝 + onSuccess 없음 → null (경로 종료)")
        void normalStepNoOnSuccessEndsPath() {
            WorkflowStep s = step("a", WorkflowStep.StepType.LLM_CALL, null, null);
            assertThat(engine.resolveNextStep(s, Map.of("output", "x"))).isNull();
        }

        @Test
        @DisplayName("ROUTER + next_step 없음 → null")
        void routerNoNextStepNull() {
            WorkflowStep s = step("r", WorkflowStep.StepType.ROUTER, null, null);
            assertThat(engine.resolveNextStep(s, Map.of("output", ""))).isNull();
        }
    }

    @Nested
    @DisplayName("resolveEntryStep — 시작 노드")
    class ResolveEntryStep {
        @Test
        @DisplayName("triggerConfig.entry_step 명시 시 우선")
        void explicitEntry() {
            WorkflowEntity e = wf("cyclic");
            e.setTriggerConfig(Map.of("entry_step", "start_here"));
            List<WorkflowStep> steps = List.of(
                    step("a", WorkflowStep.StepType.LLM_CALL, null, null),
                    step("start_here", WorkflowStep.StepType.LLM_CALL, List.of("a"), null));
            assertThat(engine.resolveEntryStep(e, steps)).isEqualTo("start_here");
        }

        @Test
        @DisplayName("명시 없으면 dependsOn 없는 첫 스텝")
        void firstRootStep() {
            WorkflowEntity e = wf("cyclic");
            e.setTriggerConfig(Map.of());
            List<WorkflowStep> steps = List.of(
                    step("a", WorkflowStep.StepType.LLM_CALL, List.of("seed"), null),
                    step("seed", WorkflowStep.StepType.LLM_CALL, null, null));
            assertThat(engine.resolveEntryStep(e, steps)).isEqualTo("seed");
        }

        @Test
        @DisplayName("triggerConfig null + 모두 의존 있음 → steps[0] 폴백")
        void fallbackToFirst() {
            WorkflowEntity e = wf("cyclic");
            List<WorkflowStep> steps = List.of(
                    step("a", WorkflowStep.StepType.LLM_CALL, List.of("b"), null),
                    step("b", WorkflowStep.StepType.LLM_CALL, List.of("a"), null));
            assertThat(engine.resolveEntryStep(e, steps)).isEqualTo("a");
        }
    }

    @Nested
    @DisplayName("resolveStepBudget — 무한루프 방어 상한")
    class ResolveStepBudget {
        @Test
        @DisplayName("미지정 → 기본 50")
        void defaultBudget() {
            assertThat(engine.resolveStepBudget(wf("cyclic")))
                    .isEqualTo(WorkflowEngine.STEP_BUDGET_DEFAULT);
        }

        @Test
        @DisplayName("config override 적용")
        void overrideBudget() {
            WorkflowEntity e = wf("cyclic");
            e.setTriggerConfig(Map.of("max_total_steps", 30));
            assertThat(engine.resolveStepBudget(e)).isEqualTo(30);
        }

        @Test
        @DisplayName("절대 상한 200 으로 clamp")
        void clampToCeiling() {
            WorkflowEntity e = wf("cyclic");
            e.setTriggerConfig(Map.of("max_total_steps", 9999));
            assertThat(engine.resolveStepBudget(e)).isEqualTo(WorkflowEngine.STEP_BUDGET_CEILING);
        }

        @Test
        @DisplayName("0 이하 비정상값 → 기본으로 복구")
        void invalidFallsBackToDefault() {
            WorkflowEntity e = wf("cyclic");
            e.setTriggerConfig(Map.of("max_total_steps", -5));
            assertThat(engine.resolveStepBudget(e)).isEqualTo(WorkflowEngine.STEP_BUDGET_DEFAULT);
        }
    }
}
