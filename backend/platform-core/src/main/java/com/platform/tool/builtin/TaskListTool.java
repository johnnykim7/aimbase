package com.platform.tool.builtin;

import com.platform.domain.SubagentRunEntity;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.repository.SubagentRunRepository;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-033 PRD-226: 태스크 목록 조회.
 */
@Component
public class TaskListTool implements EnhancedToolExecutor {

    private final SubagentRunRepository subagentRunRepository;

    public TaskListTool(SubagentRunRepository subagentRunRepository) {
        this.subagentRunRepository = subagentRunRepository;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "task_list",
                "현재 세션의 백그라운드 태스크 목록을 조회합니다. 상태별 필터링이 가능합니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "status_filter", Map.of("type", "string",
                                        "enum", List.of("running", "completed", "failed"),
                                        "description", "상태 필터 (선택)")
                        ),
                        "required", List.of()
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("task_list",
                List.of("task-management", "agent-thinking"));
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String sessionId = ctx.sessionId();
        String statusFilter = (String) input.get("status_filter");

        List<SubagentRunEntity> entities;
        if (statusFilter != null && !statusFilter.isBlank()) {
            entities = subagentRunRepository.findByParentSessionIdAndStatus(
                    sessionId, statusFilter.toUpperCase());
        } else {
            entities = subagentRunRepository.findByParentSessionIdOrderByStartedAtDesc(sessionId);
        }

        List<Map<String, Object>> tasks = entities.stream().map(e -> {
            Map<String, Object> m = new HashMap<>();
            m.put("task_id", e.getId().toString());
            m.put("status", e.getStatus().toLowerCase());
            m.put("description", e.getTaskDescription() != null ? e.getTaskDescription() : e.getDescription());
            m.put("priority", e.getPriority());
            m.put("duration_ms", e.getDurationMs());
            m.put("created_at", e.getStartedAt() != null ? e.getStartedAt().toString() : null);
            return m;
        }).toList();

        return ToolResult.ok(
                Map.of("tasks", tasks, "total", tasks.size()),
                tasks.size() + " tasks found" +
                        (statusFilter != null ? " (filter: " + statusFilter + ")" : "")
        );
    }
}
