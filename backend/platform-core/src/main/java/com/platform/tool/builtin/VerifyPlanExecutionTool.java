package com.platform.tool.builtin;

import com.platform.agent.PlanService;
import com.platform.domain.PlanEntity;
import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-033 PRD-224: 계획 대비 실행 검증.
 * step별 결과 매칭, 완료율 산출, 미완료 gap 식별.
 */
@Component
public class VerifyPlanExecutionTool implements EnhancedToolExecutor {

    private final PlanService planService;

    public VerifyPlanExecutionTool(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "verify_plan_execution",
                "계획의 각 step과 실제 실행 결과를 비교하여 검증합니다. " +
                        "완료율을 산출하고 미완료/이탈 항목을 식별합니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "plan_id", Map.of("type", "string", "description", "대상 Plan ID"),
                                "step_results", Map.of("type", "array",
                                        "items", Map.of("type", "object",
                                                "properties", Map.of(
                                                        "step_id", Map.of("type", "string"),
                                                        "result", Map.of("type", "string"),
                                                        "status", Map.of("type", "string",
                                                                "enum", List.of("done", "skipped", "failed"))
                                                )),
                                        "description", "각 step의 실행 결과")
                        ),
                        "required", List.of("plan_id")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("verify_plan_execution",
                List.of("planning", "agent-thinking", "verification"));
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String planId = (String) input.get("plan_id");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stepResults =
                (List<Map<String, Object>>) input.get("step_results");

        try {
            PlanEntity plan = planService.verify(UUID.fromString(planId), stepResults);
            Map<String, Object> result = plan.getVerificationResult();

            return ToolResult.ok(
                    Map.of(
                            "plan_id", plan.getId().toString(),
                            "status", plan.getStatus().name(),
                            "completion_rate", result.get("completion_rate"),
                            "verified_steps", result.get("verified_steps"),
                            "total_steps", result.get("total_steps"),
                            "gaps", result.get("gaps")
                    ),
                    "Verification: " + result.get("completion_rate") + "% complete, " +
                            result.get("verified_steps") + "/" + result.get("total_steps") + " steps verified."
            );
        } catch (IllegalStateException e) {
            return ToolResult.error("Cannot verify plan: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Plan not found: " + planId);
        }
    }
}
