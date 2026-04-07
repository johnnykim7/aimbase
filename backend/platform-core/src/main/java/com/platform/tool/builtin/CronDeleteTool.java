package com.platform.tool.builtin;

import com.platform.llm.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.workflow.CronScheduleManager;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-035 PRD-229: Cron 작업 삭제.
 */
@Component
public class CronDeleteTool implements EnhancedToolExecutor {

    private final CronScheduleManager cronScheduleManager;

    public CronDeleteTool(CronScheduleManager cronScheduleManager) {
        this.cronScheduleManager = cronScheduleManager;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "cron_delete",
                "등록된 Cron 스케줄 작업을 삭제합니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "job_id", Map.of("type", "string",
                                        "description", "삭제할 스케줄 작업 ID")
                        ),
                        "required", List.of("job_id")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "cron_delete", "1.0", ToolScope.BUILTIN,
                PermissionLevel.RESTRICTED_WRITE,
                false, false, true, true,
                RetryPolicy.NONE,
                List.of("automation", "scheduling"),
                List.of("delete", "schedule")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String jobId = (String) input.get("job_id");
        if (jobId == null || jobId.isBlank()) {
            return ToolResult.error("job_id는 필수입니다");
        }

        try {
            cronScheduleManager.deleteJob(jobId);
            return ToolResult.ok(
                    Map.of("deleted", jobId),
                    "스케줄 '" + jobId + "' 삭제 완료"
            );
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
    }
}
