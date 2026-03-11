package com.platform.workflow.step;

import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CONDITION 스텝 실행기.
 * 조건식을 평가하여 다음 실행할 스텝을 결정.
 *
 * config 형식:
 * {
 *   "expression": "{{s1.output}} contains '완료'",
 *   "true_step": "step3",    // 조건 참일 때 이동할 스텝 ID
 *   "false_step": "step4"   // 조건 거짓일 때 이동할 스텝 ID
 * }
 *
 * 지원 연산자:
 * - "{{ref}} contains 'str'"  — 문자열 포함 검사 (대소문자 무시)
 * - "{{ref}} equals 'str'"    — 동등 비교
 * - "{{ref}} > N" / "< N" / ">= N" / "<= N" — 숫자 비교
 */
@Component
public class ConditionStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(ConditionStepExecutor.class);

    @Override
    public WorkflowStep.StepType supports() {
        return WorkflowStep.StepType.CONDITION;
    }

    @Override
    public Map<String, Object> execute(WorkflowStep step, StepContext context) {
        Map<String, Object> config = step.config();

        String expressionTemplate = (String) config.getOrDefault("expression", "true");
        String trueStep = (String) config.get("true_step");
        String falseStep = (String) config.get("false_step");

        // 변수 치환 후 조건 평가
        String expression = context.resolve(expressionTemplate);
        boolean result = evaluate(expression);

        String nextStep = result ? trueStep : falseStep;
        log.debug("CONDITION step '{}': '{}' → {} → next='{}'",
                step.id(), expression, result, nextStep);

        return Map.of(
                "output", String.valueOf(result),
                "result", result,
                "next_step", nextStep != null ? nextStep : "",
                "expression", expression
        );
    }

    // ─── 조건식 평가 ─────────────────────────────────────────────────────

    private boolean evaluate(String expression) {
        if (expression == null || expression.isBlank()) return true;

        String expr = expression.strip();

        // "value contains 'str'"
        if (expr.toLowerCase().contains(" contains ")) {
            int idx = expr.toLowerCase().indexOf(" contains ");
            String left = expr.substring(0, idx).strip();
            String right = extractQuoted(expr.substring(idx + 10).strip());
            return left.toLowerCase().contains(right.toLowerCase());
        }

        // "value equals 'str'"
        if (expr.toLowerCase().contains(" equals ")) {
            int idx = expr.toLowerCase().indexOf(" equals ");
            String left = expr.substring(0, idx).strip();
            String right = extractQuoted(expr.substring(idx + 8).strip());
            return left.equalsIgnoreCase(right);
        }

        // Numeric comparisons: >=, <=, >, <
        for (String op : new String[]{">=", "<=", ">", "<"}) {
            int idx = expr.indexOf(op);
            if (idx > 0) {
                String leftStr = expr.substring(0, idx).strip();
                String rightStr = expr.substring(idx + op.length()).strip();
                try {
                    double left = Double.parseDouble(leftStr);
                    double right = Double.parseDouble(rightStr);
                    return switch (op) {
                        case ">=" -> left >= right;
                        case "<=" -> left <= right;
                        case ">"  -> left > right;
                        case "<"  -> left < right;
                        default -> false;
                    };
                } catch (NumberFormatException e) {
                    log.warn("Cannot parse numeric comparison in expression: '{}'", expression);
                    return false;
                }
            }
        }

        // Boolean literals
        if ("true".equalsIgnoreCase(expr)) return true;
        if ("false".equalsIgnoreCase(expr)) return false;

        // 빈 문자열 검사
        return !expr.isEmpty();
    }

    /** 따옴표로 감싸진 문자열 추출: 'value' or "value" */
    private String extractQuoted(String s) {
        if (s == null) return "";
        if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
