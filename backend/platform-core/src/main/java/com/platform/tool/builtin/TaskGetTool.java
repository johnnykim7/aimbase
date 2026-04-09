package com.platform.tool.builtin;

import com.platform.domain.SubagentRunEntity;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.repository.SubagentRunRepository;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-033 PRD-226: 태스크 상세 조회.
 */
@Component
public class TaskGetTool implements EnhancedToolExecutor {

    private final SubagentRunRepository subagentRunRepository;

    public TaskGetTool(SubagentRunRepository subagentRunRepository) {
        this.subagentRunRepository = subagentRunRepository;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "task_get",
                "백그라운드 태스크의 상세 정보를 조회합니다. 상태, 출력, 토큰 사용량, 실행 시간을 확인할 수 있습니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "task_id", Map.of("type", "string", "description", "조회할 Task ID")
                        ),
                        "required", List.of("task_id")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("task_get",
                List.of("task-management", "agent-thinking"));
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String taskId = (String) input.get("task_id");

        return subagentRunRepository.findById(UUID.fromString(taskId))
                .map(entity -> {
                    Map<String, Object> data = new HashMap<>();
                    data.put("task_id", entity.getId().toString());
                    data.put("status", entity.getStatus().toLowerCase());
                    data.put("description", entity.getTaskDescription());
                    data.put("output", entity.getOutput());
                    data.put("token_usage", Map.of(
                            "input_tokens", entity.getInputTokens(),
                            "output_tokens", entity.getOutputTokens()
                    ));
                    data.put("duration_ms", entity.getDurationMs());
                    data.put("created_at", entity.getStartedAt() != null
                            ? entity.getStartedAt().toString() : null);
                    data.put("error", entity.getError());
                    return ToolResult.ok(data,
                            "Task " + taskId + ": " + entity.getStatus().toLowerCase());
                })
                .orElse(ToolResult.error("Task not found: " + taskId));
    }
}
