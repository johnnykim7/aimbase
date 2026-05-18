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

    /** CR-084 P1: 조건식 평가는 공용 ExpressionEvaluator 에 위임 (ROUTER 와 평가기 공유). */
    private final ExpressionEvaluator expressionEvaluator;

    public ConditionStepExecutor(ExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
    }

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

        // 변수 치환 후 조건 평가 (CR-084 P1: 평가기 위임, 동작 동일)
        String expression = context.resolve(expressionTemplate);
        boolean result = expressionEvaluator.evaluate(expression);

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

}
