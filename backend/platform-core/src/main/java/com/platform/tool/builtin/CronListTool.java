package com.platform.tool.builtin;

import com.platform.domain.master.ScheduledJobEntity;
import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.workflow.CronScheduleManager;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-035 PRD-229: 등록된 Cron 작업 목록 조회.
 */
@Component
public class CronListTool implements EnhancedToolExecutor {

    private final CronScheduleManager cronScheduleManager;

    public CronListTool(CronScheduleManager cronScheduleManager) {
        this.cronScheduleManager = cronScheduleManager;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "cron_list",
                "현재 테넌트에 등록된 Cron 스케줄 작업 목록을 조회합니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(),
                        "required", List.of()
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "cron_list", "1.0", ToolScope.BUILTIN,
                PermissionLevel.READ_ONLY,
                false, true, false, true,
                RetryPolicy.NONE,
                List.of("automation", "scheduling"),
                List.of("read", "list")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String tenantId = ctx.tenantId();
        if (tenantId == null) {
            return ToolResult.error("테넌트 컨텍스트가 필요합니다");
        }

        List<ScheduledJobEntity> jobs = cronScheduleManager.listJobs(tenantId);

        List<Map<String, Object>> result = jobs.stream()
                .map(ScheduledJobEntity::toMap)
                .toList();

        return ToolResult.ok(
                result,
                jobs.size() + "개 스케줄 작업 조회 완료"
        );
    }
}
