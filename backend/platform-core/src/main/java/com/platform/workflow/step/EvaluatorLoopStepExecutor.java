package com.platform.workflow.step;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EVALUATOR_LOOP 스텝 실행기 (CR-055).
 *
 * <p>Anthropic "Building Effective Agents"의 Evaluator-Optimizer 패턴.
 * 한 노드 내부에서 generator → evaluator 루프를 반복하며, pass_criteria 통과 또는
 * max_iterations 소진 시 종료한다.
 *
 * <h3>구성</h3>
 * <pre>
 * config:
 *   max_iterations: 3        // 1~10, 초과 시 10으로 clamp (BIZ-110)
 *   generator:               // LLM_CALL과 동일한 설정 (model, connection_id, system, prompt, max_tokens, response_schema)
 *   evaluator:               // LLM_CALL과 동일 + response_format JSON 강제
 *   pass_criteria:
 *     type: SCORE_THRESHOLD | JSONPATH_MATCH | LLM_JUDGE
 *     field: "score"         // (SCORE_THRESHOLD) evaluator 출력 JSON의 필드 경로
 *     threshold: 8.5         // (SCORE_THRESHOLD) 통과 임계값
 *     operator: GTE          // GTE | GT | LTE | LT | EQ
 *     jsonpath: "$.passed"   // (JSONPATH_MATCH) dot-path, "$.foo.bar"
 *     expected: true         // (JSONPATH_MATCH) 비교 대상
 * </pre>
 *
 * <h3>루프 변수 주입</h3>
 * generator/evaluator 프롬프트에서 {{loop.*}} 네임스페이스로 iteration 내부 변수 참조 가능:
 * <ul>
 *   <li>{{loop.iteration}} — 0부터 시작하는 현재 반복 인덱스</li>
 *   <li>{{loop.previous_output}} — 직전 iteration의 generator 출력</li>
 *   <li>{{loop.feedback}} — 직전 iteration의 evaluator feedback</li>
 *   <li>{{loop.generator_output}} — (evaluator 시점) 현재 iteration의 generator 출력</li>
 * </ul>
 *
 * <h3>실패 정책</h3>
 * <ul>
 *   <li>Generator 호출 실패: iteration 실패로 기록 후 다음 진행. 연속 3회 실패 시 스텝 FAIL.</li>
 *   <li>Evaluator 호출 실패: 1회 재시도 (Q1 정책). 재시도 실패 시 해당 iteration verdict=null, 다음 진행.</li>
 *   <li>Evaluator JSON 파싱 실패: 재시도 불가 (동일 결과 예상), verdict=null로 기록.</li>
 *   <li>pass_criteria 설정 오류 (field 없음 등): 스텝 FAIL (사용자에게 드러내야 하는 오류).</li>
 * </ul>
 */
@Component
public class EvaluatorLoopStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(EvaluatorLoopStepExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** BIZ-110: max_iterations 하드 상한 (WorkflowValidator에서도 참조). */
    public static final int MAX_ITERATIONS_CEILING = 10;
    /** 설정 누락 시 기본 반복 횟수 */
    static final int DEFAULT_MAX_ITERATIONS = 3;
    /** Generator 연속 실패 허용 횟수 — 초과 시 스텝 FAIL */
    static final int GENERATOR_CONSECUTIVE_FAILURE_LIMIT = 3;

    private final LlmCallStepExecutor llmCallStepExecutor;
    private final com.platform.service.PromptTemplateService promptTemplateService;

    public EvaluatorLoopStepExecutor(LlmCallStepExecutor llmCallStepExecutor,
                                     com.platform.service.PromptTemplateService promptTemplateService) {
        this.llmCallStepExecutor = llmCallStepExecutor;
        this.promptTemplateService = promptTemplateService;
    }

    @Override
    public WorkflowStep.StepType supports() {
        return WorkflowStep.StepType.EVALUATOR_LOOP;
    }

    @Override
    public Map<String, Object> execute(WorkflowStep step, StepContext context) {
        Map<String, Object> config = step.config();

        int maxIterations = clampIterations(step.id(), config.get("max_iterations"));
        Map<String, Object> generatorConfig = requireSection(config, "generator", step.id());
        Map<String, Object> evaluatorConfig = requireSection(config, "evaluator", step.id());
        Map<String, Object> passCriteria = requireSection(config, "pass_criteria", step.id());

        // evaluator 비대칭성 권장 — 같은 model이면 경고 로그 (런타임 차단은 하지 않음)
        warnIfSymmetric(step.id(), generatorConfig, evaluatorConfig);

        // evaluator 프롬프트 템플릿 치환 (prompt_template_key 지정 시)
        evaluatorConfig = resolveEvaluatorPrompt(step.id(), evaluatorConfig);

        List<Map<String, Object>> iterations = new ArrayList<>();
        String prevOutput = null;
        String prevFeedback = null;
        Map<String, Object> finalVerdict = null;
        String finalOutput = null;
        Object finalStructuredData = null;
        boolean loopExhausted = true;
        int finalIteration = -1;
        int genFailureStreak = 0;
        long startedAt = System.currentTimeMillis();
        int totalInputTokens = 0;
        int totalOutputTokens = 0;

        for (int i = 0; i < maxIterations; i++) {
            Map<String, Object> iterRecord = new LinkedHashMap<>();
            iterRecord.put("index", i);

            // ─ 1) Generator 실행 ─────────────────────────────────
            Map<String, Object> genLoopVars = new LinkedHashMap<>();
            genLoopVars.put("iteration", i);
            genLoopVars.put("previous_output", prevOutput != null ? prevOutput : "");
            genLoopVars.put("feedback", prevFeedback != null ? prevFeedback : "");

            Map<String, Object> genResult;
            try {
                WorkflowStep genStep = buildVirtualLlmStep(step.id() + ".gen." + i, generatorConfig);
                StepContext genCtx = context.withLoopVars(genLoopVars);
                long genStart = System.currentTimeMillis();
                genResult = llmCallStepExecutor.execute(genStep, genCtx);
                iterRecord.put("generator_ms", System.currentTimeMillis() - genStart);
                genFailureStreak = 0;
            } catch (Exception e) {
                genFailureStreak++;
                log.warn("EVALUATOR_LOOP[{}] iter {} generator failed (streak {}/{}): {}",
                        step.id(), i, genFailureStreak, GENERATOR_CONSECUTIVE_FAILURE_LIMIT, e.getMessage());
                iterRecord.put("generator_error", e.getMessage());
                iterations.add(iterRecord);
                if (genFailureStreak >= GENERATOR_CONSECUTIVE_FAILURE_LIMIT) {
                    throw new RuntimeException("EVALUATOR_LOOP step '" + step.id() + "': generator failed "
                            + genFailureStreak + " consecutive times. Aborting.", e);
                }
                continue;
            }

            String genOutput = genResult.getOrDefault("output", "").toString();
            iterRecord.put("generator_output", genOutput);
            iterRecord.put("generator_structured_data", genResult.get("structured_data"));
            totalInputTokens += asInt(genResult.get("input_tokens"));
            totalOutputTokens += asInt(genResult.get("output_tokens"));

            // ─ 2) Evaluator 실행 (1회 재시도) ─────────────────────
            Map<String, Object> evalLoopVars = new LinkedHashMap<>(genLoopVars);
            evalLoopVars.put("generator_output", genOutput);
            evalLoopVars.put("previous_feedback", prevFeedback != null ? prevFeedback : "");

            Map<String, Object> verdict = null;
            WorkflowStep evalStep = buildVirtualLlmStep(step.id() + ".eval." + i, evaluatorConfig);
            StepContext evalCtx = context.withLoopVars(evalLoopVars);

            Map<String, Object> evalResult = null;
            boolean retried = false;
            try {
                long evalStart = System.currentTimeMillis();
                evalResult = llmCallStepExecutor.execute(evalStep, evalCtx);
                iterRecord.put("evaluator_ms", System.currentTimeMillis() - evalStart);
            } catch (Exception first) {
                log.warn("EVALUATOR_LOOP[{}] iter {} evaluator failed, retrying once: {}",
                        step.id(), i, first.getMessage());
                try {
                    long evalStart = System.currentTimeMillis();
                    evalResult = llmCallStepExecutor.execute(evalStep, evalCtx);
                    iterRecord.put("evaluator_ms", System.currentTimeMillis() - evalStart);
                    retried = true;
                    iterRecord.put("evaluator_retried", true);
                } catch (Exception second) {
                    log.warn("EVALUATOR_LOOP[{}] iter {} evaluator retry also failed: {}",
                            step.id(), i, second.getMessage());
                    iterRecord.put("evaluator_error", second.getMessage());
                }
            }

            if (evalResult != null) {
                verdict = parseVerdict(evalResult);
                if (verdict == null) {
                    iterRecord.put("evaluator_parse_failed", true);
                } else {
                    iterRecord.put("evaluator_verdict", verdict);
                }
                totalInputTokens += asInt(evalResult.get("input_tokens"));
                totalOutputTokens += asInt(evalResult.get("output_tokens"));
            }

            iterations.add(iterRecord);
            finalIteration = i;

            // ─ 3) pass_criteria 평가 ──────────────────────────────
            if (verdict != null && evaluatePassCriteria(step.id(), passCriteria, verdict)) {
                log.info("EVALUATOR_LOOP[{}] passed at iteration {}/{} (retried={})",
                        step.id(), i, maxIterations - 1, retried);
                finalOutput = genOutput;
                finalStructuredData = genResult.get("structured_data");
                finalVerdict = verdict;
                loopExhausted = false;
                break;
            }

            // 통과 못 함 → 다음 iteration 준비
            prevOutput = genOutput;
            prevFeedback = verdict != null ? asString(verdict.get("feedback")) : null;
        }

        // 루프 소진 시 마지막 generator 출력을 최종 output으로 사용
        if (finalOutput == null && !iterations.isEmpty()) {
            for (int i = iterations.size() - 1; i >= 0; i--) {
                Object gen = iterations.get(i).get("generator_output");
                if (gen != null) {
                    finalOutput = gen.toString();
                    finalStructuredData = iterations.get(i).get("generator_structured_data");
                    finalIteration = (int) iterations.get(i).get("index");
                    break;
                }
            }
        }

        if (finalOutput == null) {
            throw new RuntimeException("EVALUATOR_LOOP step '" + step.id() +
                    "': no successful generator output across " + maxIterations + " iterations");
        }

        long totalDuration = System.currentTimeMillis() - startedAt;
        log.info("EVALUATOR_LOOP[{}] completed: {} iterations, exhausted={}, tokens={}in/{}out, {}ms",
                step.id(), iterations.size(), loopExhausted, totalInputTokens, totalOutputTokens, totalDuration);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("output", finalOutput);
        if (finalStructuredData != null) {
            result.put("structured_data", finalStructuredData);
        }
        if (finalVerdict != null) {
            result.put("final_verdict", finalVerdict);
        }
        result.put("iterations", iterations);
        result.put("final_iteration", finalIteration);
        result.put("loop_exhausted", loopExhausted);
        result.put("pass_criteria_type", passCriteria.get("type"));
        result.put("total_duration_ms", totalDuration);
        result.put("total_input_tokens", totalInputTokens);
        result.put("total_output_tokens", totalOutputTokens);
        return result;
    }

    // ═══════════════════════════════════════════════
    // pass_criteria 평가
    // ═══════════════════════════════════════════════

    /** verdict이 pass_criteria를 만족하면 true. 설정 오류 시 RuntimeException. */
    boolean evaluatePassCriteria(String stepId, Map<String, Object> criteria, Map<String, Object> verdict) {
        String type = asString(criteria.get("type"));
        if (type == null) {
            throw new RuntimeException("EVALUATOR_LOOP step '" + stepId + "': pass_criteria.type is required");
        }

        return switch (type) {
            case "SCORE_THRESHOLD" -> evaluateScoreThreshold(stepId, criteria, verdict);
            case "JSONPATH_MATCH" -> evaluateJsonPathMatch(stepId, criteria, verdict);
            case "LLM_JUDGE" -> evaluateLlmJudge(verdict);
            default -> throw new RuntimeException(
                    "EVALUATOR_LOOP step '" + stepId + "': unknown pass_criteria.type '" + type + "'");
        };
    }

    private boolean evaluateScoreThreshold(String stepId, Map<String, Object> criteria, Map<String, Object> verdict) {
        String field = asString(criteria.getOrDefault("field", "score"));
        Object thresholdObj = criteria.get("threshold");
        if (thresholdObj == null) {
            throw new RuntimeException("EVALUATOR_LOOP step '" + stepId +
                    "': SCORE_THRESHOLD requires 'threshold'");
        }
        double threshold = ((Number) thresholdObj).doubleValue();
        String operator = asString(criteria.getOrDefault("operator", "GTE"));

        Object raw = resolveDotPath(verdict, field);
        if (!(raw instanceof Number number)) {
            throw new RuntimeException("EVALUATOR_LOOP step '" + stepId +
                    "': field '" + field + "' is not numeric in evaluator verdict (got " + raw + ")");
        }
        double value = number.doubleValue();
        return switch (operator) {
            case "GTE" -> value >= threshold;
            case "GT" -> value > threshold;
            case "LTE" -> value <= threshold;
            case "LT" -> value < threshold;
            case "EQ" -> value == threshold;
            default -> throw new RuntimeException(
                    "EVALUATOR_LOOP step '" + stepId + "': unknown operator '" + operator + "'");
        };
    }

    private boolean evaluateJsonPathMatch(String stepId, Map<String, Object> criteria, Map<String, Object> verdict) {
        String path = asString(criteria.get("jsonpath"));
        if (path == null) {
            throw new RuntimeException("EVALUATOR_LOOP step '" + stepId +
                    "': JSONPATH_MATCH requires 'jsonpath'");
        }
        // 간소화: "$.foo.bar" 형식의 dot-path만 지원 (배열 인덱스/필터 등 복합 JSONPath는 후속 CR)
        String normalized = path.startsWith("$.") ? path.substring(2) : path.startsWith("$") ? path.substring(1) : path;
        Object actual = resolveDotPath(verdict, normalized);
        Object expected = criteria.get("expected");
        if (expected == null) {
            // expected 미지정 → "non-null / truthy" 의미
            return isTruthy(actual);
        }
        return expected.equals(actual);
    }

    private boolean evaluateLlmJudge(Map<String, Object> verdict) {
        Object passed = verdict.get("passed");
        return isTruthy(passed);
    }

    /** "foo.bar" 같은 dot-path를 Map에서 재귀적으로 해석. */
    Object resolveDotPath(Map<String, Object> map, String path) {
        if (path == null || path.isBlank()) return null;
        String[] parts = path.split("\\.");
        Object cursor = map;
        for (String part : parts) {
            if (cursor instanceof Map<?, ?> m) {
                cursor = m.get(part);
            } else {
                return null;
            }
        }
        return cursor;
    }

    private boolean isTruthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof String s) return !s.isBlank() && !"false".equalsIgnoreCase(s);
        if (v instanceof java.util.Collection<?> c) return !c.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    // ═══════════════════════════════════════════════
    // 내부 헬퍼 — LlmCallStepExecutor 재사용 지원
    // ═══════════════════════════════════════════════

    /** generator/evaluator 설정 Map을 LLM_CALL 가상 스텝으로 조립. */
    private WorkflowStep buildVirtualLlmStep(String virtualId, Map<String, Object> llmConfig) {
        return new WorkflowStep(
                virtualId,
                virtualId,
                WorkflowStep.StepType.LLM_CALL,
                llmConfig,
                List.of(),
                null,
                null,
                null
        );
    }

    /**
     * evaluator.prompt_template_key가 지정된 경우 PromptTemplateService로 본문을 로드하여
     * prompt 필드에 주입한다. 이미 prompt가 있으면 별도 처리 없이 반환.
     *
     * <p>response_format → response_schema 매핑도 수행: evaluator.response_format.schema를
     * LlmCallStepExecutor가 기대하는 response_schema 필드로 복제.
     */
    Map<String, Object> resolveEvaluatorPrompt(String stepId, Map<String, Object> evaluatorConfig) {
        Map<String, Object> resolved = new LinkedHashMap<>(evaluatorConfig);

        // prompt_template_key → prompt 본문 로드
        String templateKey = asString(resolved.get("prompt_template_key"));
        boolean hasInlinePrompt = resolved.containsKey("prompt") && !asString(resolved.get("prompt")).isBlank();
        if (templateKey != null && !templateKey.isBlank()) {
            if (hasInlinePrompt) {
                throw new RuntimeException("EVALUATOR_LOOP step '" + stepId +
                        "': evaluator.prompt_template_key XOR evaluator.prompt — exactly one required");
            }
            String body = promptTemplateService.getTemplateOrFallback(templateKey, null);
            if (body == null) {
                throw new RuntimeException("EVALUATOR_LOOP step '" + stepId +
                        "': prompt template not found: " + templateKey);
            }
            resolved.put("prompt", body);
        } else if (!hasInlinePrompt) {
            throw new RuntimeException("EVALUATOR_LOOP step '" + stepId +
                    "': evaluator.prompt or evaluator.prompt_template_key required");
        }

        // response_format.schema → response_schema 복제 (LlmCallStepExecutor 호환)
        if (!resolved.containsKey("response_schema")) {
            Object rf = resolved.get("response_format");
            if (rf instanceof Map<?, ?> rfMap) {
                Object schema = rfMap.get("schema");
                if (schema instanceof Map) {
                    resolved.put("response_schema", schema);
                }
            }
        }
        return resolved;
    }

    /** evaluator 응답에서 verdict Map 추출 (structured_data 우선, 없으면 text JSON 파싱). */
    @SuppressWarnings("unchecked")
    Map<String, Object> parseVerdict(Map<String, Object> evalResult) {
        Object structured = evalResult.get("structured_data");
        if (structured instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        String text = asString(evalResult.get("output"));
        if (text == null || text.isBlank()) return null;
        try {
            return MAPPER.readValue(text, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("EVALUATOR_LOOP evaluator output is not valid JSON: {}", e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════
    // 설정 검증 헬퍼
    // ═══════════════════════════════════════════════

    int clampIterations(String stepId, Object raw) {
        if (raw == null) return DEFAULT_MAX_ITERATIONS;
        int n = ((Number) raw).intValue();
        if (n < 1) {
            throw new RuntimeException("EVALUATOR_LOOP step '" + stepId +
                    "': max_iterations must be >= 1 (got " + n + ")");
        }
        if (n > MAX_ITERATIONS_CEILING) {
            log.warn("EVALUATOR_LOOP[{}] max_iterations {} exceeds ceiling {}, clamped",
                    stepId, n, MAX_ITERATIONS_CEILING);
            return MAX_ITERATIONS_CEILING;
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requireSection(Map<String, Object> config, String key, String stepId) {
        Object v = config.get(key);
        if (!(v instanceof Map)) {
            throw new RuntimeException("EVALUATOR_LOOP step '" + stepId +
                    "': config." + key + " is required and must be an object");
        }
        return (Map<String, Object>) v;
    }

    private void warnIfSymmetric(String stepId, Map<String, Object> gen, Map<String, Object> eval) {
        String genModel = asString(gen.get("model"));
        String evalModel = asString(eval.get("model"));
        if (genModel != null && genModel.equals(evalModel)) {
            log.warn("EVALUATOR_LOOP[{}] generator and evaluator use the same model '{}'. " +
                    "Asymmetry is recommended — self-evaluation tends to produce small improvements.",
                    stepId, genModel);
        }
    }

    // ═══════════════════════════════════════════════
    // 타입 변환 유틸
    // ═══════════════════════════════════════════════

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static int asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return 0;
    }
}
