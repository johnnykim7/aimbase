package com.platform.workflow.step;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 워크플로우 조건식 평가기.
 *
 * <p>CR-084 P1: 기존 {@code ConditionStepExecutor} 내부에 있던 조건식 평가 로직을
 * 별도 컴포넌트로 추출. CONDITION 스텝과 신규 ROUTER 스텝(CR-084 P2)이 동일한
 * 평가기를 공유하기 위함이다.
 *
 * <p><b>동작 보존</b>: 본 클래스의 {@link #evaluate(String)} / {@link #extractQuoted(String)}
 * 는 추출 전 {@code ConditionStepExecutor} 의 동명 메서드와 라인 단위로 동일하다.
 * 기존 CONDITION 워크플로우의 평가 결과는 바뀌지 않는다.
 *
 * <p>지원 연산자:
 * <ul>
 *   <li>{@code value contains 'str'}  — 문자열 포함 검사 (대소문자 무시)</li>
 *   <li>{@code value equals 'str'}    — 동등 비교 (대소문자 무시)</li>
 *   <li>{@code value >= N} / {@code <=} / {@code >} / {@code <} — 숫자 비교</li>
 *   <li>{@code true} / {@code false}  — 불리언 리터럴</li>
 *   <li>그 외 — 빈 문자열이 아니면 true</li>
 * </ul>
 */
@Component
public class ExpressionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ExpressionEvaluator.class);

    /**
     * 변수 치환이 끝난 조건식 문자열을 평가하여 boolean 반환.
     *
     * @param expression 치환 완료된 표현식 (null/공백이면 true)
     * @return 평가 결과
     */
    public boolean evaluate(String expression) {
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
    public String extractQuoted(String s) {
        if (s == null) return "";
        if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
