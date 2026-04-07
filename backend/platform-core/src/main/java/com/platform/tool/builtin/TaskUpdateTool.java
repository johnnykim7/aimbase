package com.platform.tool.builtin;

import com.platform.llm.model.UnifiedToolDef;
import com.platform.repository.SubagentRunRepository;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-033 PRD-227: 태스크 메타데이터 수정.
 */
@Component
public class TaskUpdateTool implements EnhancedToolExecutor {

    private final SubagentRunRepository subagentRunRepository;

    public TaskUpdateTool(SubagentRunRepository subagentRunRepository) {
        this.subagentRunRepository = subagentRunRepository;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "task_update",
                "백그라운드 태스크의 메타데이터를 수정합니다. 설명이나 우선순위를 변경할 수 있습니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "task_id", Map.of("type", "string", "description", "대상 Task ID"),
                                "description", Map.of("type", "string", "description", "변경할 설명"),
                                "priority", Map.of("type", "string",
                                        "enum", List.of("low", "medium", "high"),
                                        "description", "변경할 우선순위")
                        ),
                        "required", List.of("task_id")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "task_update", "1.0", ToolScope.NATIVE,
                PermissionLevel.RESTRICTED_WRITE,
                false, false, false, true,
                RetryPolicy.NONE,
                List.of("task-management", "agent-thinking"),
                List.of("update", "task-management")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String taskId = (String) input.get("task_id");

        return subagentRunRepository.findById(UUID.fromString(taskId))
                .map(entity -> {
                    // COMPLETED/CANCELLED 태스크 수정 불가
                    if ("COMPLETED".equals(entity.getStatus()) || "CANCELLED".equals(entity.getStatus())) {
                        return ToolResult.error("Cannot update " + entity.getStatus().toLowerCase() + " task");
                    }

                    List<String> updatedFields = new ArrayList<>();
                    if (input.containsKey("description")) {
                        entity.setTaskDescription((String) input.get("description"));
                        updatedFields.add("description");
                    }
                    if (input.containsKey("priority")) {
                        entity.setPriority((String) input.get("priority"));
                        updatedFields.add("priority");
                    }

                    subagentRunRepository.save(entity);
                    return ToolResult.ok(
                            Map.of("task_id", taskId, "updated_fields", updatedFields),
                            "Task updated: " + String.join(", ", updatedFields)
                    );
                })
                .orElse(ToolResult.error("Task not found: " + taskId));
    }
}
