package com.platform.tool.builtin;

import com.platform.agent.PlanService;
import com.platform.domain.PlanEntity;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * CR-033 PRD-222: 계획 모드 진입.
 * BIZ-052: readOnlyMode 활성화, 쓰기 도구 자동 차단.
 * BIZ-053: 세션당 활성 Plan 1개 제한.
 */
@Component
public class EnterPlanModeTool implements EnhancedToolExecutor {

    private static final String PLAN_MODE_KEY_PREFIX = "session:planMode:";

    private final PlanService planService;
    private final RedisTemplate<String, String> redisTemplate;

    public EnterPlanModeTool(PlanService planService,
                             RedisTemplate<String, String> redisTemplate) {
        this.planService = planService;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "enter_plan_mode",
                "계획 모드로 진입합니다. 읽기 전용 도구만 사용 가능하며, 파일 쓰기/편집은 차단됩니다. " +
                        "복잡한 작업을 수행하기 전에 먼저 탐색하고 계획을 수립할 때 사용합니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string", "description", "계획 제목"),
                                "goals", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "달성 목표 리스트"),
                                "constraints", Map.of("type", "array", "items", Map.of("type", "string"),
                                        "description", "제약 조건 리스트")
                        ),
                        "required", List.of("title", "goals")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "enter_plan_mode", "1.0", ToolScope.NATIVE,
                PermissionLevel.RESTRICTED_WRITE,
                false, false, false, true,
                RetryPolicy.NONE,
                List.of("planning", "agent-thinking"),
                List.of("create", "plan-management")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String sessionId = ctx.sessionId();

        // BIZ-053: 세션당 활성 Plan 1개 제한
        if (planService.hasActivePlan(sessionId)) {
            return ToolResult.denied("Active plan already exists in this session. " +
                    "Complete or abandon the current plan first.");
        }

        String title = (String) input.get("title");
        @SuppressWarnings("unchecked")
        List<String> goals = (List<String>) input.get("goals");
        @SuppressWarnings("unchecked")
        List<String> constraints = input.containsKey("constraints")
                ? (List<String>) input.get("constraints") : List.of();

        PlanEntity plan = planService.createPlan(sessionId, title, goals, constraints);

        // BIZ-052: planModeActive 설정 → ToolCallHandler에서 readOnly 강제
        setPlanModeActive(sessionId, true);

        return ToolResult.ok(
                Map.of(
                        "plan_id", plan.getId().toString(),
                        "status", "PLANNING",
                        "message", "Plan mode activated. Only read-only tools are available. " +
                                "Use exit_plan_mode when your plan is ready."
                ),
                "Plan mode activated: " + title
        );
    }

    /** Redis에 planModeActive 플래그 저장 */
    private void setPlanModeActive(String sessionId, boolean active) {
        String key = PLAN_MODE_KEY_PREFIX + sessionId;
        if (active) {
            redisTemplate.opsForValue().set(key, "true", Duration.ofHours(24));
        } else {
            redisTemplate.delete(key);
        }
    }

    /** 외부에서 planMode 상태 조회용 static 키 패턴 */
    public static String getPlanModeKey(String sessionId) {
        return PLAN_MODE_KEY_PREFIX + sessionId;
    }
}
