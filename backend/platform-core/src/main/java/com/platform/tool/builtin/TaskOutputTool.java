package com.platform.tool.builtin;

import com.platform.tool.model.UnifiedToolDef;
import com.platform.repository.SubagentRunRepository;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-033 PRD-227: 대용량 태스크 출력 저장.
 * 컨텍스트 윈도우 외부(DB large_output)에 저장하여 토큰을 절약한다.
 */
@Component
public class TaskOutputTool implements EnhancedToolExecutor {

    private final SubagentRunRepository subagentRunRepository;

    public TaskOutputTool(SubagentRunRepository subagentRunRepository) {
        this.subagentRunRepository = subagentRunRepository;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "task_output",
                "태스크의 대용량 출력을 컨텍스트 윈도우 외부에 저장합니다. " +
                        "토큰을 절약하면서 결과를 보존할 때 사용합니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "task_id", Map.of("type", "string", "description", "대상 Task ID"),
                                "output", Map.of("type", "string", "description", "저장할 대용량 출력")
                        ),
                        "required", List.of("task_id", "output")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "task_output", "1.0", ToolScope.NATIVE,
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
        String output = (String) input.get("output");

        return subagentRunRepository.findById(UUID.fromString(taskId))
                .map(entity -> {
                    int sizeBytes = output.getBytes().length;

                    entity.setLargeOutput(Map.of(
                            "content", output,
                            "stored_at", Instant.now().toString(),
                            "size_bytes", sizeBytes
                    ));
                    subagentRunRepository.save(entity);

                    return ToolResult.ok(
                            Map.of(
                                    "task_id", taskId,
                                    "output_size_bytes", sizeBytes,
                                    "stored", true
                            ),
                            "Output stored: " + (sizeBytes / 1024) + "KB"
                    );
                })
                .orElse(ToolResult.error("Task not found: " + taskId));
    }
}
