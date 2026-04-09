package com.platform.tool.builtin;

import com.platform.agent.PlanService;
import com.platform.domain.PlanEntity;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-033 PRD-223: 계획 모드 종료.
 * steps 확정 + planModeActive 해제 + 쓰기 도구 재활성화.
 */
@Component
public class ExitPlanModeTool implements EnhancedToolExecutor {

    private final PlanService planService;
    private final RedisTemplate<String, String> redisTemplate;

    public ExitPlanModeTool(PlanService planService,
                            RedisTemplate<String, String> redisTemplate) {
        this.planService = planService;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "exit_plan_mode",
                "계획 모드를 종료하고 실행 단계로 전환합니다. " +
                        "확정된 계획의 steps를 저장하고, 쓰기 도구를 재활성화합니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "plan_id", Map.of("type", "string", "description", "대상 Plan ID"),
                                "steps", Map.of("type", "array",
                                        "items", Map.of("type", "object",
                                                "properties", Map.of(
                                                        "id", Map.of("type", "string"),
                                                        "description", Map.of("type", "string"),
                                                        "tools", Map.of("type", "array", "items", Map.of("type", "string"))
                                                )),
                                        "description", "실행 계획 스텝 배열"),
                                "summary", Map.of("type", "string", "description", "계획 요약")
                        ),
                        "required", List.of("plan_id", "steps")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "exit_plan_mode", "1.0", ToolScope.NATIVE,
                PermissionLevel.RESTRICTED_WRITE,
                false, false, false, true,
                RetryPolicy.NONE,
                List.of("planning", "agent-thinking"),
                List.of("update", "plan-management")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String planId = (String) input.get("plan_id");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) input.get("steps");
        String summary = (String) input.get("summary");

        if (steps == null || steps.isEmpty()) {
            return ToolResult.error("Steps cannot be empty. Provide at least one step.");
        }

        try {
            PlanEntity plan = planService.exitPlanMode(UUID.fromString(planId), steps, summary);

            // planModeActive 해제 → 쓰기 도구 재활성화
            redisTemplate.delete(EnterPlanModeTool.getPlanModeKey(ctx.sessionId()));

            return ToolResult.ok(
                    Map.of(
                            "plan_id", plan.getId().toString(),
                            "status", "EXECUTING",
                            "total_steps", steps.size(),
                            "message", "Plan mode exited. All tools are now available. " +
                                    "Execute your plan, then use verify_plan_execution to check results."
                    ),
                    "Plan mode exited. " + steps.size() + " steps to execute."
            );
        } catch (IllegalStateException e) {
            return ToolResult.error("Cannot exit plan mode: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Plan not found: " + planId);
        }
    }
}
