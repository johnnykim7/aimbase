package com.platform.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CR-055 WorkflowValidator 단위 테스트.
 *
 * <p>설계서 § 6.1의 7가지 검증 규칙 — 각각 거부/통과 케이스로 검증.
 */
class WorkflowValidatorTest {

    private WorkflowValidator validator;

    @BeforeEach
    void setUp() {
        validator = new WorkflowValidator();
    }

    // ═══════════════════════════════════════════════
    // 헬퍼
    // ═══════════════════════════════════════════════

    /** 기본 valid EVALUATOR_LOOP 스텝 (검증 통과). 모든 중첩 Map은 mutable. */
    private Map<String, Object> validStep() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_schema");
        responseFormat.put("schema", schema);

        Map<String, Object> evaluator = new LinkedHashMap<>();
        evaluator.put("prompt", "evaluate");
        evaluator.put("response_format", responseFormat);

        Map<String, Object> generator = new LinkedHashMap<>();
        generator.put("prompt", "generate");

        Map<String, Object> passCriteria = new LinkedHashMap<>();
        passCriteria.put("type", "SCORE_THRESHOLD");
        passCriteria.put("field", "score");
        passCriteria.put("threshold", 8.0);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("max_iterations", 3);
        config.put("generator", generator);
        config.put("evaluator", evaluator);
        config.put("pass_criteria", passCriteria);

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "loop-1");
        step.put("type", "EVALUATOR_LOOP");
        step.put("config", config);
        return step;
    }

    @SuppressWarnings("unchecked")
    private void mutate(Map<String, Object> step, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> cursor = step;
        for (int i = 0; i < parts.length - 1; i++) {
            cursor = (Map<String, Object>) cursor.get(parts[i]);
        }
        if (value == null) cursor.remove(parts[parts.length - 1]);
        else cursor.put(parts[parts.length - 1], value);
    }

    // ═══════════════════════════════════════════════
    // Baseline — 유효한 스텝은 통과
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("유효한 EVALUATOR_LOOP 스텝은 예외 없이 통과")
    void validStep_passes() {
        assertThatCode(() -> validator.validate(List.of(validStep())))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("non-EVALUATOR_LOOP 스텝은 검증 통과 (하위 호환)")
    void nonEvaluatorLoop_bypassed() {
        Map<String, Object> llmStep = Map.of(
                "id", "s1",
                "type", "LLM_CALL",
                "config", Map.of());  // 의도적으로 빈 config도 무시
        assertThatCode(() -> validator.validate(List.of(llmStep)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null 또는 빈 steps 목록은 예외 없이 통과")
    void emptySteps_passes() {
        assertThatCode(() -> validator.validate(null)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(List.of())).doesNotThrowAnyException();
    }

    // ═══════════════════════════════════════════════
    // 규칙 ①: max_iterations 범위 (1 ≤ N ≤ 10)
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("max_iterations=0 → 400 BAD_REQUEST")
    void maxIterations_zero_rejected() {
        Map<String, Object> step = validStep();
        mutate(step, "config.max_iterations", 0);

        assertThatThrownBy(() -> validator.validate(List.of(step)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("max_iterations must be between 1 and 10");
    }

    @Test
    @DisplayName("max_iterations=11 → 400 BAD_REQUEST")
    void maxIterations_exceedsCeiling_rejected() {
        Map<String, Object> step = validStep();
        mutate(step, "config.max_iterations", 11);

        assertThatThrownBy(() -> validator.validate(List.of(step)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("between 1 and 10");
    }

    @Test
    @DisplayName("max_iterations 누락 → 통과 (기본값 사용)")
    void maxIterations_missing_passes() {
        Map<String, Object> step = validStep();
        mutate(step, "config.max_iterations", null);

        assertThatCode(() -> validator.validate(List.of(step)))
                .doesNotThrowAnyException();
    }

    // ═══════════════════════════════════════════════
    // 규칙 ②: generator.prompt 비어있지 않음
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("generator.prompt 빈 문자열 → 400 BAD_REQUEST")
    void generatorPrompt_empty_rejected() {
        Map<String, Object> step = validStep();
        mutate(step, "config.generator.prompt", "   ");

        assertThatThrownBy(() -> validator.validate(List.of(step)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("generator.prompt");
    }

    // ═══════════════════════════════════════════════
    // 규칙 ③: evaluator.prompt XOR prompt_template_key
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("evaluator에 prompt와 prompt_template_key 모두 있음 → 400")
    void evaluator_bothPromptAndTemplateKey_rejected() {
        Map<String, Object> step = validStep();
        mutate(step, "config.evaluator.prompt_template_key", "evaluator.literary_critic");

        assertThatThrownBy(() -> validator.validate(List.of(step)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("XOR");
    }

    @Test
    @DisplayName("evaluator에 prompt도 prompt_template_key도 없음 → 400")
    void evaluator_neitherPromptNorTemplateKey_rejected() {
        Map<String, Object> step = validStep();
        mutate(step, "config.evaluator.prompt", null);

        assertThatThrownBy(() -> validator.validate(List.of(step)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("XOR");
    }

    @Test
    @DisplayName("evaluator.prompt_template_key만 있음 → 통과")
    void evaluator_onlyTemplateKey_passes() {
        Map<String, Object> step = validStep();
        mutate(step, "config.evaluator.prompt", null);
        mutate(step, "config.evaluator.prompt_template_key", "evaluator.literary_critic");

        assertThatCode(() -> validator.validate(List.of(step)))
                .doesNotThrowAnyException();
    }

    // ═══════════════════════════════════════════════
    // 규칙 ④: response_format.type = "json_schema"
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("response_format.type != 'json_schema' → 400")
    void responseFormat_wrongType_rejected() {
        Map<String, Object> step = validStep();
        mutate(step, "config.evaluator.response_format", Map.of("type", "text"));

        assertThatThrownBy(() -> validator.validate(List.of(step)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("json_schema");
    }

    @Test
    @DisplayName("response_format 누락 → 400")
    void responseFormat_missing_rejected() {
        Map<String, Object> step = validStep();
        mutate(step, "config.evaluator.response_format", null);

        assertThatThrownBy(() -> validator.validate(List.of(step)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("response_format");
    }

    // ═══════════════════════════════════════════════
    // 규칙 ⑤: pass_criteria.type 화이트리스트
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("pass_criteria.type='INVALID' → 400")
    void passCriteriaType_invalid_rejected() {
        Map<String, Object> step = validStep();
        mutate(step, "config.pass_criteria.type", "INVALID");

        assertThatThrownBy(() -> validator.validate(List.of(step)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("pass_criteria.type");
    }

    // ═══════════════════════════════════════════════
    // 규칙 ⑥: SCORE_THRESHOLD 필수 필드 + operator 화이트리스트
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("SCORE_THRESHOLD에 threshold 누락 → 400")
    void scoreThreshold_missingThreshold_rejected() {
        Map<String, Object> step = validStep();
        mutate(step, "config.pass_criteria.threshold", null);

        assertThatThrownBy(() -> validator.validate(List.of(step)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("threshold");
    }

    @Test
    @DisplayName("SCORE_THRESHOLD operator='INVALID' → 400")
    void scoreThreshold_invalidOperator_rejected() {
        Map<String, Object> step = validStep();
        mutate(step, "config.pass_criteria.operator", "INVALID");

        assertThatThrownBy(() -> validator.validate(List.of(step)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("operator");
    }

    // ═══════════════════════════════════════════════
    // 규칙 ⑦: JSONPATH_MATCH — jsonpath 필수
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("JSONPATH_MATCH에 jsonpath 누락 → 400")
    void jsonpathMatch_missingPath_rejected() {
        Map<String, Object> step = validStep();
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("type", "JSONPATH_MATCH");
        // jsonpath 누락
        mutate(step, "config.pass_criteria", criteria);

        assertThatThrownBy(() -> validator.validate(List.of(step)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("jsonpath");
    }

    @Test
    @DisplayName("JSONPATH_MATCH jsonpath 지정 → 통과")
    void jsonpathMatch_withPath_passes() {
        Map<String, Object> step = validStep();
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("type", "JSONPATH_MATCH");
        criteria.put("jsonpath", "$.passed");
        criteria.put("expected", true);
        mutate(step, "config.pass_criteria", criteria);

        assertThatCode(() -> validator.validate(List.of(step)))
                .doesNotThrowAnyException();
    }

    // ═══════════════════════════════════════════════
    // LLM_JUDGE는 추가 필드 없이 통과
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("LLM_JUDGE는 type만 있으면 통과")
    void llmJudge_passes() {
        Map<String, Object> step = validStep();
        mutate(step, "config.pass_criteria", Map.of("type", "LLM_JUDGE"));

        assertThatCode(() -> validator.validate(List.of(step)))
                .doesNotThrowAnyException();
    }

    // ═══════════════════════════════════════════════
    // 에러 메시지에 step id 포함
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("에러 메시지에 step id가 포함되어야 함 (FE 노출용)")
    void errorMessage_includesStepId() {
        Map<String, Object> step = validStep();
        mutate(step, "config.max_iterations", 99);

        assertThatThrownBy(() -> validator.validate(List.of(step)))
                .hasMessageContaining("loop-1");
    }
}
