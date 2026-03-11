package com.platform.workflow.step;

import com.platform.action.ActionExecutor;
import com.platform.action.model.*;
import com.platform.policy.PolicyEngine;
import com.platform.policy.model.PolicyResult;
import com.platform.workflow.StepContext;
import com.platform.workflow.model.WorkflowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ACTION 스텝 실행기.
 *
 * config 형식:
 * {
 *   "intent": "save_result",
 *   "type": "WRITE",                           // WRITE | NOTIFY | WRITE_AND_NOTIFY
 *   "adapter": "postgresql",                   // 어댑터 ID
 *   "destination": "results",                  // 테이블/채널 이름
 *   "payload": {"data": {"key": "{{s1.output}}"}}
 * }
 *
 * Phase 5: PolicyEngine.evaluate() 결과에 따라 DENY → 스텝 실패, ALLOW → 기존 실행.
 */
@Component
public class ActionStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionStepExecutor.class);

    private final ActionExecutor actionExecutor;
    private final PolicyEngine policyEngine;

    public ActionStepExecutor(ActionExecutor actionExecutor, PolicyEngine policyEngine) {
        this.actionExecutor = actionExecutor;
        this.policyEngine = policyEngine;
    }

    @Override
    public WorkflowStep.StepType supports() {
        return WorkflowStep.StepType.ACTION;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(WorkflowStep step, StepContext context) {
        Map<String, Object> config = context.resolveMap(step.config());

        String intent = (String) config.getOrDefault("intent", "workflow_action");
        String typeStr = (String) config.getOrDefault("type", "WRITE");
        String adapter = (String) config.getOrDefault("adapter", "postgresql");
        String destination = (String) config.getOrDefault("destination", "results");

        ActionRequest.ActionType type;
        try {
            type = ActionRequest.ActionType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            type = ActionRequest.ActionType.WRITE;
        }

        // payload 구성
        Object payloadObj = config.get("payload");
        Map<String, Object> payloadData = payloadObj instanceof Map
                ? (Map<String, Object>) payloadObj
                : Map.of("data", config);

        ActionTarget target = new ActionTarget(adapter, null, destination);
        ActionRequest request = ActionRequest.of(intent, type, List.of(target), payloadData);

        log.debug("ACTION step '{}': intent='{}', type={}", step.id(), intent, type);

        // Phase 5: 실행 전 PolicyEngine 평가
        PolicyResult policyResult = policyEngine.evaluate(request);

        if (policyResult.action() == PolicyResult.PolicyAction.DENY) {
            String reason = policyResult.denialReason() != null
                    ? policyResult.denialReason() : "Policy denied action";
            log.warn("ACTION step '{}' denied by policy: {}", step.id(), reason);
            throw new IllegalStateException("Policy denied: " + reason);
        }

        if (policyResult.action() == PolicyResult.PolicyAction.REQUIRE_APPROVAL) {
            // 워크플로우에서 승인이 필요한 액션은 HUMAN_INPUT 스텝을 앞에 배치하여 처리
            log.warn("ACTION step '{}' requires approval — use a HUMAN_INPUT step before this ACTION step",
                    step.id());
            throw new IllegalStateException(
                    "Action '" + intent + "' requires approval. Place a HUMAN_INPUT step before this ACTION step.");
        }

        ActionResult result = actionExecutor.execute(request, policyResult);

        boolean success = result.status() == ActionResult.ActionStatus.SUCCESS;
        log.debug("ACTION step '{}' completed: {}", step.id(), result.status());

        return Map.of(
                "output", success ? "success" : "failed",
                "status", result.status().name().toLowerCase(),
                "results", result.results().stream()
                        .map(r -> Map.of("status", r.status(), "adapter", adapter))
                        .toList()
        );
    }
}
