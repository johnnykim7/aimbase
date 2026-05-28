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

            // CR-084 P2: ROUTER config 구조 검증 (저장 단계 차단 — 실행 시점 오류 방지)
            if (WorkflowStep.StepType.ROUTER.name().equals(type)) {
                validateRouter(location, asMap(step.get("config"), location));
            }

            // CR-087: FOREACH config 구조 검증
            if (WorkflowStep.StepType.FOREACH.name().equals(type)) {
                validateForeach(location, asMap(step.get("config"), location));
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
    // ROUTER 검증 규칙 (CR-084 P2, 설계서 T3-7 § 3.3)
    // ═══════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    void validateRouter(String location, Map<String, Object> config) {
        if (config == null) {
            throw bad(location + ": ROUTER requires config");
        }

        // 1) routes 배열 존재 + 비어있지 않음
        Object rawRoutes = config.get("routes");
        if (!(rawRoutes instanceof List<?> routes)) {
            throw bad(location + ": config.routes must be an array");
        }
        if (routes.isEmpty()) {
            throw bad(location + ": config.routes must not be empty");
        }

        int defaultCount = 0;
        for (int i = 0; i < routes.size(); i++) {
            Object rawRoute = routes.get(i);
            String routeLoc = location + ".routes[" + i + "]";
            if (!(rawRoute instanceof Map<?, ?> m)) {
                throw bad(routeLoc + " must be an object");
            }
            Map<String, Object> route = (Map<String, Object>) m;

            boolean isDefault = Boolean.TRUE.equals(route.get("default"))
                    || "true".equalsIgnoreCase(String.valueOf(route.get("default")));
            String when = asString(route.get("when"));
            boolean hasWhen = when != null && !when.isBlank();

            // 2) 각 route 는 when XOR default (정확히 하나)
            if (isDefault == hasWhen) {
                throw bad(routeLoc + ": exactly one of 'when' or 'default:true' required");
            }
            if (isDefault) defaultCount++;

            // 3) to 필수 + 비어있지 않음
            String to = asString(route.get("to"));
            if (to == null || to.isBlank()) {
                throw bad(routeLoc + ": 'to' (target step id) must not be empty");
            }
        }

        // 4) default route 는 최대 1개
        if (defaultCount > 1) {
            throw bad(location + ": at most one 'default:true' route allowed (got " + defaultCount + ")");
        }
    }

    // ═══════════════════════════════════════════════
    // FOREACH 검증 규칙 (CR-087)
    // ═══════════════════════════════════════════════

    private static final Set<String> FOREACH_MODES = Set.of("sequential", "parallel");
    private static final Set<String> FOREACH_COLLECT = Set.of("append", "merge", "none");
    private static final Set<String> FOREACH_ON_ITEM_ERROR = Set.of("fail", "continue");

    void validateForeach(String location, Map<String, Object> config) {
        if (config == null) {
            throw bad(location + ": FOREACH requires config");
        }

        // 1) items 필수 (변수 참조 문자열 또는 인라인 List)
        Object items = config.get("items");
        if (items == null) {
            throw bad(location + ": config.items is required (collection reference or inline list)");
        }
        if (!(items instanceof String) && !(items instanceof List)) {
            throw bad(location + ": config.items must be a {{...}} reference string or a list");
        }

        // 2) body 필수 — step 정의 객체 + type 존재 + FOREACH 직접 중첩 금지
        Object bodyObj = config.get("body");
        if (!(bodyObj instanceof Map<?, ?> body)) {
            throw bad(location + ": config.body is required (a step definition object)");
        }
        Object bodyType = body.get("type");
        if (bodyType == null || bodyType.toString().isBlank()) {
            throw bad(location + ": config.body.type is required");
        }
        boolean validType = false;
        for (WorkflowStep.StepType t : WorkflowStep.StepType.values()) {
            if (t.name().equals(bodyType.toString())) { validType = true; break; }
        }
        if (!validType) {
            throw bad(location + ": config.body.type '" + bodyType + "' is not a valid step type");
        }
        if (WorkflowStep.StepType.FOREACH.name().equals(bodyType.toString())) {
            throw bad(location + ": nested FOREACH body is not allowed (use SUB_WORKFLOW for nesting)");
        }

        // 3) mode / collect / on_item_error 화이트리스트
        validateEnum(location, "mode", config.get("mode"), FOREACH_MODES);
        validateEnum(location, "collect", config.get("collect"), FOREACH_COLLECT);
        validateEnum(location, "on_item_error", config.get("on_item_error"), FOREACH_ON_ITEM_ERROR);

        // 4) max_items / max_concurrency 양수
        validatePositive(location, "max_items", config.get("max_items"));
        validatePositive(location, "max_concurrency", config.get("max_concurrency"));
    }

    private void validateEnum(String location, String key, Object value, Set<String> allowed) {
        if (value == null) return; // 미지정 = 기본값
        if (!allowed.contains(value.toString().toLowerCase())) {
            throw bad(location + ": config." + key + " must be one of " + allowed + " (got '" + value + "')");
        }
    }

    private void validatePositive(String location, String key, Object value) {
        if (value == null) return; // 미지정 = 기본값
        if (!(value instanceof Number n)) {
            throw bad(location + ": config." + key + " must be a number");
        }
        if (n.intValue() < 1) {
            throw bad(location + ": config." + key + " must be >= 1 (got " + n.intValue() + ")");
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
