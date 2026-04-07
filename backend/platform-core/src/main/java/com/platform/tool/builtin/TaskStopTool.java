package com.platform.tool.builtin;

import com.platform.hook.HookDispatcher;
import com.platform.hook.HookEvent;
import com.platform.hook.HookInput;
import com.platform.llm.model.UnifiedToolDef;
import com.platform.repository.SubagentRunRepository;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-033 PRD-227: 실행 중 태스크 강제 중지.
 */
@Component
public class TaskStopTool implements EnhancedToolExecutor {

    private final SubagentRunRepository subagentRunRepository;
    private final HookDispatcher hookDispatcher;

    public TaskStopTool(SubagentRunRepository subagentRunRepository, HookDispatcher hookDispatcher) {
        this.subagentRunRepository = subagentRunRepository;
        this.hookDispatcher = hookDispatcher;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "task_stop",
                "실행 중인 백그라운드 태스크를 강제 중지합니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "task_id", Map.of("type", "string", "description", "중지할 Task ID"),
                                "reason", Map.of("type", "string", "description", "중지 사유 (선택)")
                        ),
                        "required", List.of("task_id")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "task_stop", "1.0", ToolScope.NATIVE,
                PermissionLevel.RESTRICTED_WRITE,
                false, false, true, true,
                RetryPolicy.NONE,
                List.of("task-management", "agent-thinking"),
                List.of("delete", "task-management")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String taskId = (String) input.get("task_id");
        String reason = (String) input.getOrDefault("reason", "Stopped by user");

        return subagentRunRepository.findById(UUID.fromString(taskId))
                .map(entity -> {
                    if (!"RUNNING".equals(entity.getStatus())) {
                        return ToolResult.error("Task is not running (current: " +
                                entity.getStatus().toLowerCase() + ")");
                    }

                    entity.setStatus("CANCELLED");
                    entity.setError("Stopped: " + reason);
                    entity.setCompletedAt(OffsetDateTime.now());
                    subagentRunRepository.save(entity);

                    // CR-034: TASK_COMPLETED 훅 발행
                    try {
                        hookDispatcher.dispatch(HookEvent.TASK_COMPLETED,
                                HookInput.of(HookEvent.TASK_COMPLETED, ctx.sessionId(),
                                        Map.of("taskId", taskId, "status", "CANCELLED", "reason", reason),
                                        Map.of()));
                    } catch (Exception ignored) {}

                    return ToolResult.ok(
                            Map.of(
                                    "task_id", taskId,
                                    "status", "cancelled",
                                    "message", "Task stopped: " + reason
                            ),
                            "Task stopped: " + taskId
                    );
                })
                .orElse(ToolResult.error("Task not found: " + taskId));
    }
}
