package com.platform.tool.builtin;

import com.platform.agent.TodoService;
import com.platform.domain.TodoEntity;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-033 PRD-225: 세션 체크리스트.
 * BIZ-055: 전체 교체 방식 — 매 호출 시 todos 배열 전체를 받아 덮어쓴다.
 */
@Component
public class TodoWriteTool implements EnhancedToolExecutor {

    private final TodoService todoService;

    public TodoWriteTool(TodoService todoService) {
        this.todoService = todoService;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "todo_write",
                "세션 내 작업 체크리스트를 관리합니다. 전체 교체 방식으로 동작하며, " +
                        "매 호출 시 todos 배열 전체를 받아 기존 데이터를 덮어씁니다. " +
                        "복잡한 멀티스텝 작업에서 진행 상태를 추적할 때 사용합니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "todos", Map.of("type", "array",
                                        "items", Map.of("type", "object",
                                                "properties", Map.of(
                                                        "content", Map.of("type", "string",
                                                                "description", "작업 내용 (명령형)"),
                                                        "status", Map.of("type", "string",
                                                                "enum", List.of("pending", "in_progress", "completed"),
                                                                "description", "작업 상태"),
                                                        "activeForm", Map.of("type", "string",
                                                                "description", "진행형 표현 (예: 'Running tests')")
                                                ),
                                                "required", List.of("content", "status", "activeForm")),
                                        "description", "전체 Todo 배열")
                        ),
                        "required", List.of("todos")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "todo_write", "1.0", ToolScope.NATIVE,
                PermissionLevel.RESTRICTED_WRITE,
                false, false, false, true,
                RetryPolicy.NONE,
                List.of("task-tracking", "agent-thinking"),
                List.of("create", "update", "task-management")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> todosInput = (List<Map<String, Object>>) input.get("todos");

        if (todosInput == null) {
            return ToolResult.error("todos array is required");
        }

        List<TodoEntity> saved = todoService.replaceAll(ctx.sessionId(), todosInput);

        long completed = saved.stream().filter(t -> "completed".equals(t.getStatus())).count();
        long inProgress = saved.stream().filter(t -> "in_progress".equals(t.getStatus())).count();
        String summary = completed + "/" + saved.size() + " completed";

        return ToolResult.ok(
                Map.of(
                        "todos", saved.stream().map(TodoEntity::toMap).toList(),
                        "summary", summary
                ),
                summary + (inProgress > 1 ? " (warning: " + inProgress + " tasks in_progress simultaneously)" : "")
        );
    }
}
