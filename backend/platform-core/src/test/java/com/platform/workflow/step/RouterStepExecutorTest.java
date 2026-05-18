package com.platform.workflow.step;

import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-084 P2 — {@link RouterStepExecutor} 단위 테스트.
 *
 * <p>N-way 동적 라우팅: 첫 매치 채택 / default 폴백 / 매치 없음 / 변수 치환 연동.
 * 출력 계약이 CONDITION 과 동형(next_step 키)임을 함께 고정한다.
 */
@DisplayName("RouterStepExecutor — N-way 동적 라우팅")
class RouterStepExecutorTest {

    private RouterStepExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new RouterStepExecutor(new ExpressionEvaluator());
    }

    private WorkflowStep router(List<Map<String, Object>> routes) {
        return new WorkflowStep("r1", "router", WorkflowStep.StepType.ROUTER,
                Map.of("routes", routes), null, null, null, null);
    }

    private StepContext ctx(Map<String, Object> stepResults) {
        return new StepContext("run1", "wf1", "sess1", Map.of(), stepResults);
    }

    @Test
    @DisplayName("첫 매치 route 채택 (정의 순서)")
    void firstMatchWins() {
        WorkflowStep step = router(List.of(
                Map.of("when", "{{classify.output}} equals 'refund'", "to", "refund_step"),
                Map.of("when", "{{classify.output}} equals 'inquiry'", "to", "inquiry_step"),
                Map.of("default", true, "to", "fallback_step")
        ));
        StepContext context = ctx(Map.of("classify", Map.of("output", "refund")));

        Map<String, Object> result = executor.execute(step, context);

        assertThat(result.get("next_step")).isEqualTo("refund_step");
        assertThat(result.get("output")).isEqualTo("refund_step"); // CONDITION 동형 계약
    }

    @Test
    @DisplayName("두 번째 route 매치")
    void secondMatch() {
        WorkflowStep step = router(List.of(
                Map.of("when", "{{c.output}} equals 'refund'", "to", "refund_step"),
                Map.of("when", "{{c.output}} equals 'inquiry'", "to", "inquiry_step"),
                Map.of("default", true, "to", "fallback_step")
        ));
        StepContext context = ctx(Map.of("c", Map.of("output", "inquiry")));

        assertThat(executor.execute(step, context).get("next_step")).isEqualTo("inquiry_step");
    }

    @Test
    @DisplayName("매치 없으면 default route")
    void fallbackToDefault() {
        WorkflowStep step = router(List.of(
                Map.of("when", "{{c.output}} equals 'refund'", "to", "refund_step"),
                Map.of("default", true, "to", "fallback_step")
        ));
        StepContext context = ctx(Map.of("c", Map.of("output", "something-else")));

        assertThat(executor.execute(step, context).get("next_step")).isEqualTo("fallback_step");
    }

    @Test
    @DisplayName("default route 가 when 보다 우선하지 않음 (정의 순서 무관)")
    void defaultDoesNotPreempt() {
        WorkflowStep step = router(List.of(
                Map.of("default", true, "to", "fallback_step"),
                Map.of("when", "{{c.output}} equals 'hit'", "to", "hit_step")
        ));
        StepContext context = ctx(Map.of("c", Map.of("output", "hit")));

        assertThat(executor.execute(step, context).get("next_step")).isEqualTo("hit_step");
    }

    @Test
    @DisplayName("매치 없고 default 도 없으면 빈 next_step")
    void noMatchNoDefault() {
        WorkflowStep step = router(List.of(
                Map.of("when", "{{c.output}} equals 'x'", "to", "x_step")
        ));
        StepContext context = ctx(Map.of("c", Map.of("output", "y")));

        assertThat(executor.execute(step, context).get("next_step")).isEqualTo("");
    }

    @Test
    @DisplayName("contains 등 ExpressionEvaluator 문법 공유 (CR-084 P1)")
    void sharesExpressionGrammar() {
        WorkflowStep step = router(List.of(
                Map.of("when", "{{c.output}} contains 'urgent'", "to", "urgent_step"),
                Map.of("default", true, "to", "normal_step")
        ));
        StepContext context = ctx(Map.of("c", Map.of("output", "this is URGENT please")));

        assertThat(executor.execute(step, context).get("next_step")).isEqualTo("urgent_step");
    }

    @Test
    @DisplayName("supports() 는 ROUTER")
    void supportsRouter() {
        assertThat(executor.supports()).isEqualTo(WorkflowStep.StepType.ROUTER);
    }
}
