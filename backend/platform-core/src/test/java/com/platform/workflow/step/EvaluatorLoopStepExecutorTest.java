package com.platform.workflow.step;

import com.platform.service.PromptTemplateService;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CR-055 EvaluatorLoopStepExecutor 단위 테스트.
 *
 * <p>LlmCallStepExecutor를 mock으로 주입해 iteration 시나리오를 직접 제어한다.
 * 실제 LLM 호출은 발생하지 않으며, executor의 루프 제어 / pass_criteria / 재시도 / clamp 로직만 검증.
 */
@ExtendWith(MockitoExtension.class)
class EvaluatorLoopStepExecutorTest {

    @Mock private LlmCallStepExecutor llmCallStepExecutor;
    @Mock private PromptTemplateService promptTemplateService;

    private EvaluatorLoopStepExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new EvaluatorLoopStepExecutor(llmCallStepExecutor, promptTemplateService);
    }

    // ═══════════════════════════════════════════════
    // 테스트 헬퍼
    // ═══════════════════════════════════════════════

    private WorkflowStep buildStep(int maxIter, String criteriaType) {
        return buildStep(maxIter, criteriaType, Map.of());
    }

    private WorkflowStep buildStep(int maxIter, String criteriaType, Map<String, Object> extraCriteria) {
        Map<String, Object> generator = new LinkedHashMap<>();
        generator.put("model", "claude-haiku");
        generator.put("prompt", "draft: {{input.source}}");

        Map<String, Object> evaluator = new LinkedHashMap<>();
        evaluator.put("model", "claude-sonnet");
        evaluator.put("prompt", "evaluate: {{loop.generator_output}}");
        evaluator.put("response_format", Map.of("type", "json_schema",
                "schema", Map.of("type", "object")));

        Map<String, Object> passCriteria = new LinkedHashMap<>();
        passCriteria.put("type", criteriaType);
        if ("SCORE_THRESHOLD".equals(criteriaType)) {
            passCriteria.put("field", "score");
            passCriteria.put("threshold", 8.5);
            passCriteria.put("operator", "GTE");
        }
        passCriteria.putAll(extraCriteria);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("max_iterations", maxIter);
        config.put("generator", generator);
        config.put("evaluator", evaluator);
        config.put("pass_criteria", passCriteria);

        return new WorkflowStep("eval-step", "Eval", WorkflowStep.StepType.EVALUATOR_LOOP,
                config, List.of(), null, null, null);
    }

    private StepContext defaultContext() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("source", "한국어 원문");
        return new StepContext("run-1", "wf-1", "sess-1", input, Map.of());
    }

    /** LLM 호출 결과를 structured_data + output 형식으로 조립. */
    private Map<String, Object> llmResult(String output, Map<String, Object> structured) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("output", output);
        if (structured != null) r.put("structured_data", structured);
        r.put("model", "mock-model");
        r.put("input_tokens", 100);
        r.put("output_tokens", 50);
        return r;
    }

    private Map<String, Object> verdict(double score, boolean passed, String feedback) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("score", score);
        v.put("passed", passed);
        v.put("feedback", feedback);
        return v;
    }

    // ═══════════════════════════════════════════════
    // 1. SCORE_THRESHOLD 1회차 통과
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("SCORE_THRESHOLD: 1회차에 임계값 넘으면 즉시 종료")
    void scoreThreshold_passImmediately() {
        WorkflowStep step = buildStep(3, "SCORE_THRESHOLD");

        when(llmCallStepExecutor.execute(any(), any()))
                .thenReturn(llmResult("first translation", null))          // gen #0
                .thenReturn(llmResult("verdict-json", verdict(9.0, true, "excellent"))); // eval #0

        Map<String, Object> result = executor.execute(step, defaultContext());

        assertThat(result.get("output")).isEqualTo("first translation");
        assertThat(result.get("loop_exhausted")).isEqualTo(false);
        assertThat(result.get("final_iteration")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> iterations = (List<Map<String, Object>>) result.get("iterations");
        assertThat(iterations).hasSize(1);
        verify(llmCallStepExecutor, times(2)).execute(any(), any()); // gen + eval 각 1회
    }

    // ═══════════════════════════════════════════════
    // 2. SCORE_THRESHOLD 3회 소진 후 종료
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("SCORE_THRESHOLD: max_iterations 소진 시 loop_exhausted=true, 마지막 gen이 output")
    void scoreThreshold_exhaust() {
        WorkflowStep step = buildStep(3, "SCORE_THRESHOLD");

        when(llmCallStepExecutor.execute(any(), any()))
                // iter 0
                .thenReturn(llmResult("draft v1", null))
                .thenReturn(llmResult("v1", verdict(5.0, false, "weak")))
                // iter 1
                .thenReturn(llmResult("draft v2", null))
                .thenReturn(llmResult("v2", verdict(7.0, false, "better but still")))
                // iter 2
                .thenReturn(llmResult("draft v3", null))
                .thenReturn(llmResult("v3", verdict(8.0, false, "almost")));

        Map<String, Object> result = executor.execute(step, defaultContext());

        assertThat(result.get("output")).isEqualTo("draft v3");
        assertThat(result.get("loop_exhausted")).isEqualTo(true);
        assertThat(result.get("final_iteration")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> iterations = (List<Map<String, Object>>) result.get("iterations");
        assertThat(iterations).hasSize(3);
    }

    // ═══════════════════════════════════════════════
    // 3. 2회차 통과 + 이전 피드백 주입 확인
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("SCORE_THRESHOLD: 2회차 통과 — iterations[1]이 최종")
    void scoreThreshold_passSecondIteration() {
        WorkflowStep step = buildStep(3, "SCORE_THRESHOLD");

        when(llmCallStepExecutor.execute(any(), any()))
                .thenReturn(llmResult("draft v1", null))
                .thenReturn(llmResult("v1", verdict(7.0, false, "needs more rhythm")))
                .thenReturn(llmResult("draft v2 better", null))
                .thenReturn(llmResult("v2", verdict(9.0, true, "great")));

        Map<String, Object> result = executor.execute(step, defaultContext());

        assertThat(result.get("output")).isEqualTo("draft v2 better");
        assertThat(result.get("loop_exhausted")).isEqualTo(false);
        assertThat(result.get("final_iteration")).isEqualTo(1);
    }

    // ═══════════════════════════════════════════════
    // 4. JSONPATH_MATCH 통과
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("JSONPATH_MATCH: $.passed=true일 때 통과")
    void jsonpathMatch_pass() {
        Map<String, Object> extra = Map.of("jsonpath", "$.passed", "expected", true);
        WorkflowStep step = buildStep(3, "JSONPATH_MATCH", extra);

        when(llmCallStepExecutor.execute(any(), any()))
                .thenReturn(llmResult("output", null))
                .thenReturn(llmResult("v", Map.of("passed", true, "detail", "ok")));

        Map<String, Object> result = executor.execute(step, defaultContext());

        assertThat(result.get("loop_exhausted")).isEqualTo(false);
        assertThat(result.get("final_iteration")).isEqualTo(0);
    }

    // ═══════════════════════════════════════════════
    // 5. LLM_JUDGE — passed 필드 그대로
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("LLM_JUDGE: passed=true면 즉시 종료")
    void llmJudge_pass() {
        WorkflowStep step = buildStep(3, "LLM_JUDGE");

        when(llmCallStepExecutor.execute(any(), any()))
                .thenReturn(llmResult("draft", null))
                .thenReturn(llmResult("v", Map.of("passed", true, "feedback", "ok")));

        Map<String, Object> result = executor.execute(step, defaultContext());

        assertThat(result.get("loop_exhausted")).isEqualTo(false);
        assertThat(result.get("final_iteration")).isEqualTo(0);
    }

    // ═══════════════════════════════════════════════
    // 6. Generator 1회 실패 → 다음 iter 성공
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("Generator 첫 iter 실패 → 다음 iter 진행 → 성공")
    void generatorFails_continuesNextIteration() {
        WorkflowStep step = buildStep(3, "SCORE_THRESHOLD");

        when(llmCallStepExecutor.execute(any(), any()))
                // iter 0: gen 실패
                .thenThrow(new RuntimeException("network error"))
                // iter 1: gen+eval 정상, 통과
                .thenReturn(llmResult("good draft", null))
                .thenReturn(llmResult("v", verdict(9.0, true, "good")));

        Map<String, Object> result = executor.execute(step, defaultContext());

        assertThat(result.get("output")).isEqualTo("good draft");
        assertThat(result.get("loop_exhausted")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> iterations = (List<Map<String, Object>>) result.get("iterations");
        assertThat(iterations).hasSize(2);
        assertThat(iterations.get(0)).containsKey("generator_error");
    }

    // ═══════════════════════════════════════════════
    // 7. Evaluator JSON 파싱 실패 (재시도 없음)
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("Evaluator 응답이 JSON 아님 → verdict=null → 다음 iter 진행")
    void evaluatorJsonParseFailure_continuesWithoutRetry() {
        WorkflowStep step = buildStep(2, "SCORE_THRESHOLD");

        when(llmCallStepExecutor.execute(any(), any()))
                // iter 0
                .thenReturn(llmResult("draft v1", null))
                .thenReturn(llmResult("not valid json at all", null))  // structured_data 없음 + text도 JSON 아님
                // iter 1 — 통과
                .thenReturn(llmResult("draft v2", null))
                .thenReturn(llmResult("v", verdict(9.0, true, "ok")));

        Map<String, Object> result = executor.execute(step, defaultContext());

        assertThat(result.get("final_iteration")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> iterations = (List<Map<String, Object>>) result.get("iterations");
        assertThat(iterations.get(0)).containsEntry("evaluator_parse_failed", true);
    }

    // ═══════════════════════════════════════════════
    // 8. max_iterations=15 → clamp=10
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("max_iterations=15 → BIZ-110 상한 10으로 clamp")
    void maxIterations_clampedToCeiling() {
        WorkflowStep step = buildStep(15, "SCORE_THRESHOLD");

        // 모든 iteration에서 통과 못 함 → 정확히 10회 반복되어야 함
        when(llmCallStepExecutor.execute(any(), any()))
                .thenAnswer(inv -> {
                    // invocation 번호로 gen/eval 구분 (짝수=gen, 홀수=eval)
                    return llmResult("out", verdict(3.0, false, "low"));
                });

        Map<String, Object> result = executor.execute(step, defaultContext());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> iterations = (List<Map<String, Object>>) result.get("iterations");
        assertThat(iterations).hasSize(10);
        assertThat(result.get("loop_exhausted")).isEqualTo(true);
    }

    // ═══════════════════════════════════════════════
    // 9. operator=GT 경계값 — 정확히 같으면 통과 안 됨
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("operator=GT, threshold=8.5, score=8.5 → 통과 실패")
    void scoreThreshold_gtBoundary_exactValueFails() {
        Map<String, Object> extra = Map.of("operator", "GT");
        WorkflowStep step = buildStep(1, "SCORE_THRESHOLD", extra);

        when(llmCallStepExecutor.execute(any(), any()))
                .thenReturn(llmResult("draft", null))
                .thenReturn(llmResult("v", verdict(8.5, false, "exact boundary")));

        Map<String, Object> result = executor.execute(step, defaultContext());

        assertThat(result.get("loop_exhausted")).isEqualTo(true);
    }

    // ═══════════════════════════════════════════════
    // 10. Evaluator 1회 재시도 (Q1 정책)
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("Evaluator 첫 호출 실패 → 1회 재시도 → 성공 → retried 플래그")
    void evaluatorTransientFailure_retriesOnce() {
        WorkflowStep step = buildStep(1, "SCORE_THRESHOLD");

        when(llmCallStepExecutor.execute(any(), any()))
                .thenReturn(llmResult("draft", null))                         // gen #0 — 정상
                .thenThrow(new RuntimeException("transient"))                 // eval #0 첫 시도 실패
                .thenReturn(llmResult("v", verdict(9.0, true, "good")));      // eval #0 재시도 성공

        Map<String, Object> result = executor.execute(step, defaultContext());

        assertThat(result.get("loop_exhausted")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> iterations = (List<Map<String, Object>>) result.get("iterations");
        assertThat(iterations.get(0)).containsEntry("evaluator_retried", true);
        // gen 1회 + eval 2회 = 총 3회 호출
        verify(llmCallStepExecutor, times(3)).execute(any(), any());
    }
}
