package com.platform.tool.builtin;

import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 간단한 사칙연산 표현식을 평가하는 내장 도구.
 * 지원 연산: +, -, *, / (정수 및 소수)
 */
@Component
public class CalculatorTool implements ToolExecutor {

    private static final UnifiedToolDef DEFINITION = new UnifiedToolDef(
            "calculate",
            "간단한 수학 표현식을 계산합니다. 예: '3.14 * 2', '100 / 4 + 5'",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "expression", Map.of(
                                    "type", "string",
                                    "description", "계산할 수학 표현식 (예: '2 + 3 * 4')"
                            )
                    ),
                    "required", List.of("expression")
            )
    );

    @Override
    public UnifiedToolDef getDefinition() {
        return DEFINITION;
    }

    @Override
    public String execute(Map<String, Object> input) {
        String expression = String.valueOf(input.getOrDefault("expression", ""));
        try {
            double result = evaluate(expression.trim());
            // 정수이면 정수 형태로 표시
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                return String.valueOf((long) result);
            }
            return String.valueOf(result);
        } catch (Exception e) {
            return "계산 오류: " + e.getMessage();
        }
    }

    /**
     * 간단한 재귀 하강 파서로 +, -, *, / 지원.
     * 괄호는 지원하지 않음 (Phase 2 built-in 수준).
     */
    private double evaluate(String expr) {
        // 덧셈/뺄셈 (우선순위 낮음)
        int i = findLastAddSub(expr);
        if (i >= 0) {
            double left = evaluate(expr.substring(0, i).trim());
            double right = evaluate(expr.substring(i + 1).trim());
            return expr.charAt(i) == '+' ? left + right : left - right;
        }
        // 곱셈/나눗셈 (우선순위 높음)
        int j = findLastMulDiv(expr);
        if (j >= 0) {
            double left = evaluate(expr.substring(0, j).trim());
            double right = evaluate(expr.substring(j + 1).trim());
            if (expr.charAt(j) == '/') {
                if (right == 0) throw new ArithmeticException("division by zero");
                return left / right;
            }
            return left * right;
        }
        return Double.parseDouble(expr);
    }

    private int findLastAddSub(String expr) {
        for (int i = expr.length() - 1; i >= 0; i--) {
            char c = expr.charAt(i);
            // 부호가 아닌 실제 연산자인지 확인 (앞에 숫자 또는 공백이 있어야 함)
            if ((c == '+' || c == '-') && i > 0) {
                char prev = expr.charAt(i - 1);
                if (Character.isDigit(prev) || prev == ' ') return i;
            }
        }
        return -1;
    }

    private int findLastMulDiv(String expr) {
        for (int i = expr.length() - 1; i >= 0; i--) {
            char c = expr.charAt(i);
            if (c == '*' || c == '/') return i;
        }
        return -1;
    }
}
