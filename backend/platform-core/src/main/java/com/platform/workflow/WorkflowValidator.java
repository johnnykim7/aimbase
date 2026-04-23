package com.platform.workflow;

import com.platform.workflow.step.EvaluatorLoopStepExecutor;
import com.platform.workflow.model.WorkflowStep;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 워크플로우 생성/수정 시 스텝 config 유효성 검증.
 *
 * <p>CR-055: EVALUATOR_LOOP 스텝의 구조적 검증을 담당. 실행 시점까지 오류를 미루면
 * 비싼 LLM 호출 후에야 설정 오류가 드러나므로, 저장 단계에서 차단한다.
 *
 * <p>기존 스텝 타입(LLM_CALL 등)은 별도 검증 없이 통과 — 과도한 스키마 검증은
 * 실행기 내부에서 이미 처리하고 있고, 기존 워크플로우 하위 호환성을 깨지 않기 위함.
 */
@Component
public class WorkflowValidator {

    private static final Set<String> PASS_CRITERIA_TYPES = Set.of("SCORE_THRESHOLD", "JSONPATH_MATCH", "LLM_JUDGE");
    private static final Set<String> SCORE_OPERATORS = Set.of("GTE", "GT", "LTE", "LT", "EQ");

    /**
     * 워크플로우 전체 steps 목록을 검증.
     * 실패 시 HTTP 400 Bad Request로 변환되는 ResponseStatusException 발생.
     */
    public void validate(List<Map<String, Object>> steps) {
        if (steps == null) return;
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            String stepId = asString(step.get("id"));
            String location = stepId != null ? "step '" + stepId + "'" : "step[" + i + "]";
            String type = asString(step.get("type"));
            if (type == null) continue; // 타입 누락은 기존 @Valid에서 처리

            if (WorkflowStep.StepType.EVALUATOR_LOOP.name().equals(type)) {
                validateEvaluatorLoop(location, asMap(step.get("config"), location));
            }
        }
    }

    // ═══════════════════════════════════════════════
    // EVALUATOR_LOOP 검증 규칙 (CR-055 설계서 § 6.1)
    // ═══════════════════════════════════════════════

    void validateEvaluatorLoop(String location, Map<String, Object> config) {
        if (config == null) {
            throw bad(location + ": EVALUATOR_LOOP requires config");
        }

        // 1) max_iterations 범위 (1 ≤ N ≤ 10)
        Object iter = config.get("max_iterations");
        if (iter != null) {
            if (!(iter instanceof Number n)) {
                throw bad(location + ": config.max_iterations must be a number");
            }
            int v = n.intValue();
            if (v < 1 || v > EvaluatorLoopStepExecutor.MAX_ITERATIONS_CEILING) {
                throw bad(location + ": config.max_iterations must be between 1 and " +
                        EvaluatorLoopStepExecutor.MAX_ITERATIONS_CEILING + " (got " + v + ")");
            }
        }

        // 2) generator 섹션 존재 + prompt 비어있지 않음
        Map<String, Object> generator = asMap(config.get("generator"), location + ".config.generator");
        if (generator == null) throw bad(location + ": config.generator is required");
        String genPrompt = asString(generator.get("prompt"));
        if (genPrompt == null || genPrompt.isBlank()) {
            throw bad(location + ": config.generator.prompt must not be empty");
        }

        // 3) evaluator 섹션 존재
        Map<String, Object> evaluator = asMap(config.get("evaluator"), location + ".config.evaluator");
        if (evaluator == null) throw bad(location + ": config.evaluator is required");

        // 4) prompt_template_key XOR prompt (정확히 하나)
        String evalPrompt = asString(evaluator.get("prompt"));
        String templateKey = asString(evaluator.get("prompt_template_key"));
        boolean hasInline = evalPrompt != null && !evalPrompt.isBlank();
        boolean hasTemplate = templateKey != null && !templateKey.isBlank();
        if (hasInline == hasTemplate) {
            throw bad(location + ": evaluator.prompt_template_key XOR evaluator.prompt — exactly one required");
        }

        // 5) response_format.type == "json_schema" 필수 (evaluator 출력 구조화 강제)
        Map<String, Object> responseFormat = asMap(evaluator.get("response_format"), location + ".config.evaluator.response_format");
        if (responseFormat == null) {
            throw bad(location + ": evaluator.response_format is required (type=json_schema)");
        }
        String rfType = asString(responseFormat.get("type"));
        if (!"json_schema".equals(rfType)) {
            throw bad(location + ": evaluator.response_format.type must be 'json_schema' (got '" + rfType + "')");
        }

        // 6) pass_criteria.type ∈ {SCORE_THRESHOLD, JSONPATH_MATCH, LLM_JUDGE}
        Map<String, Object> passCriteria = asMap(config.get("pass_criteria"), location + ".config.pass_criteria");
        if (passCriteria == null) throw bad(location + ": config.pass_criteria is required");
        String criteriaType = asString(passCriteria.get("type"));
        if (criteriaType == null || !PASS_CRITERIA_TYPES.contains(criteriaType)) {
            throw bad(location + ": pass_criteria.type must be one of " + PASS_CRITERIA_TYPES + " (got '" + criteriaType + "')");
        }

        // 7) SCORE_THRESHOLD 전용 규칙: field + threshold 필수, operator는 화이트리스트
        if ("SCORE_THRESHOLD".equals(criteriaType)) {
            String field = asString(passCriteria.getOrDefault("field", "score"));
            if (field.isBlank()) {
                throw bad(location + ": pass_criteria.field must not be empty for SCORE_THRESHOLD");
            }
            Object threshold = passCriteria.get("threshold");
            if (!(threshold instanceof Number)) {
                throw bad(location + ": pass_criteria.threshold is required (number) for SCORE_THRESHOLD");
            }
            Object op = passCriteria.get("operator");
            if (op != null && !SCORE_OPERATORS.contains(op.toString())) {
                throw bad(location + ": pass_criteria.operator must be one of " + SCORE_OPERATORS + " (got '" + op + "')");
            }
        }

        // JSONPATH_MATCH 전용: jsonpath 필수 (expected는 선택)
        if ("JSONPATH_MATCH".equals(criteriaType)) {
            String path = asString(passCriteria.get("jsonpath"));
            if (path == null || path.isBlank()) {
                throw bad(location + ": pass_criteria.jsonpath is required for JSONPATH_MATCH");
            }
        }
    }

    // ═══════════════════════════════════════════════
    // 유틸
    // ═══════════════════════════════════════════════

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v, String location) {
        if (v == null) return null;
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw bad(location + " must be an object");
    }
}
