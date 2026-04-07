package com.platform.tool.builtin;

import com.platform.domain.master.ScheduledJobEntity;
import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.workflow.CronScheduleManager;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-035 PRD-229: Cron 작업 생성/수정.
 * LLM이 자율적으로 워크플로우/도구의 반복 실행을 스케줄링한다.
 */
@Component
public class ScheduleCronTool implements EnhancedToolExecutor {

    private final CronScheduleManager cronScheduleManager;

    public ScheduleCronTool(CronScheduleManager cronScheduleManager) {
        this.cronScheduleManager = cronScheduleManager;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "schedule_cron",
                "Cron 스케줄 작업을 생성합니다. 워크플로우 또는 도구를 cron 표현식 기반으로 " +
                        "반복/일회성 자동 실행합니다. 예: 매일 오전 9시 보고서 생성.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string",
                                        "description", "스케줄 이름 (예: '일일 보고서 생성')"),
                                "cron_expression", Map.of("type", "string",
                                        "description", "Spring cron 표현식 (초 분 시 일 월 요일). 예: '0 0 9 * * *' = 매일 09시"),
                                "target_type", Map.of("type", "string",
                                        "enum", List.of("WORKFLOW", "TOOL"),
                                        "description", "실행 대상 타입"),
                                "target_id", Map.of("type", "string",
                                        "description", "실행 대상 ID (워크플로우 ID 또는 도구 이름)"),
                                "input", Map.of("type", "object",
                                        "description", "실행 시 전달할 입력 파라미터 (선택)")
                        ),
                        "required", List.of("name", "cron_expression", "target_type", "target_id")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "schedule_cron", "1.0", ToolScope.BUILTIN,
                PermissionLevel.RESTRICTED_WRITE,
                false, false, false, true,
                RetryPolicy.NONE,
                List.of("automation", "scheduling"),
                List.of("create", "schedule")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String name = (String) input.get("name");
        String cronExpression = (String) input.get("cron_expression");
        String targetType = (String) input.get("target_type");
        String targetId = (String) input.get("target_id");
        Map<String, Object> inputPayload = (Map<String, Object>) input.get("input");

        if (name == null || cronExpression == null || targetType == null || targetId == null) {
            return ToolResult.error("name, cron_expression, target_type, target_id는 필수입니다");
        }

        String tenantId = ctx.tenantId();
        if (tenantId == null) {
            return ToolResult.error("테넌트 컨텍스트가 필요합니다");
        }

        try {
            ScheduledJobEntity job = cronScheduleManager.createJob(
                    name, cronExpression, targetType, targetId, inputPayload, tenantId);

            return ToolResult.ok(
                    job.toMap(),
                    "스케줄 '" + name + "' 등록 완료 (cron: " + cronExpression + ")"
            );
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
