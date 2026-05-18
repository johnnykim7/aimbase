package com.platform.workflow.step;

import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-084 P2 — ROUTER 스텝 실행기.
 *
 * <p>CONDITION 의 true_step/false_step 2갈래 분기를 N-way 로 일반화한다.
 * 런타임에 변수 치환된 표현식(또는 직전 LLM 출력)을 평가하여 N개 후보 중
 * 하나의 대상 스텝을 동적으로 선택한다.
 *
 * <p>config 형식:
 * <pre>
 * {
 *   "routes": [
 *     { "when": "{{classify.output}} equals 'refund'",  "to": "refund_step" },
 *     { "when": "{{classify.output}} contains 'urgent'", "to": "urgent_step" },
 *     { "default": true, "to": "fallback_step" }
 *   ]
 * }
 * </pre>
 *
 * <p>선택 규칙:
 * <ul>
 *   <li>routes 를 정의 순서대로 평가, {@code when} 표현식이 참인 <b>첫</b> route 채택</li>
 *   <li>매치되는 when 이 없으면 {@code default:true} route 채택 (없으면 빈 next_step)</li>
 *   <li>{@code when}/{@code default} 둘 다 없는 route 는 무시 (검증에서 사전 차단)</li>
 * </ul>
 *
 * <p>출력 계약은 {@link ConditionStepExecutor} 와 동형({@code next_step} 키) —
 * 워크플로우 스케줄러가 CONDITION 과 동일 경로로 분기를 처리한다.
 *
 * <p>평가기는 {@link ExpressionEvaluator} 를 공유(CR-084 P1) — CONDITION 과 동일 문법.
 */
@Component
public class RouterStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(RouterStepExecutor.class);

    private final ExpressionEvaluator expressionEvaluator;

    public RouterStepExecutor(ExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
    }

    @Override
    public WorkflowStep.StepType supports() {
        return WorkflowStep.StepType.ROUTER;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(WorkflowStep step, StepContext context) {
        Map<String, Object> config = step.config();
        Object rawRoutes = config != null ? config.get("routes") : null;
        if (!(rawRoutes instanceof List<?> routes) || routes.isEmpty()) {
            // 검증에서 차단되지만 방어적으로 빈 분기 반환
            log.warn("ROUTER step '{}': no routes defined", step.id());
            return result(step, "", null);
        }

        String defaultTarget = null;

        for (Object rawRoute : routes) {
            if (!(rawRoute instanceof Map<?, ?> route)) continue;
            Map<String, Object> r = (Map<String, Object>) route;

            String to = r.get("to") != null ? r.get("to").toString() : null;

            // default route 는 보류 — when route 가 모두 실패할 때만 사용
            if (Boolean.TRUE.equals(r.get("default")) || "true".equalsIgnoreCase(String.valueOf(r.get("default")))) {
                if (defaultTarget == null) defaultTarget = to;
                continue;
            }

            Object whenObj = r.get("when");
            if (whenObj == null) continue; // when/default 둘 다 없음 → 무시

            String resolved = context.resolve(whenObj.toString());
            if (expressionEvaluator.evaluate(resolved)) {
                log.debug("ROUTER step '{}': route matched ('{}' → {}) → next='{}'",
                        step.id(), whenObj, resolved, to);
                return result(step, to != null ? to : "", whenObj.toString());
            }
        }

        // 매치 없음 → default route
        log.debug("ROUTER step '{}': no route matched → default='{}'", step.id(), defaultTarget);
        return result(step, defaultTarget != null ? defaultTarget : "", "default");
    }

    /** CONDITION 과 동형 출력 계약 (next_step 키) — 스케줄러가 동일 분기 처리. */
    private Map<String, Object> result(WorkflowStep step, String nextStep, String matchedWhen) {
        return Map.of(
                "output", nextStep,
                "next_step", nextStep,
                "matched", matchedWhen != null ? matchedWhen : ""
        );
    }
}
