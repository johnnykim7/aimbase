package com.platform.tool.builtin;

import com.platform.domain.SubagentRunEntity;
import com.platform.llm.model.UnifiedToolDef;
import com.platform.repository.SubagentRunRepository;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-033 PRD-226: 백그라운드 태스크 생성.
 * SubagentRunner를 래핑하여 Task 인터페이스를 제공한다.
 * BIZ-056: 세션당 동시 실행 태스크 5개 제한.
 */
@Component
public class TaskCreateTool implements EnhancedToolExecutor {

    private static final int MAX_CONCURRENT_TASKS = 5;

    private final SubagentRunRepository subagentRunRepository;

    public TaskCreateTool(SubagentRunRepository subagentRunRepository) {
        this.subagentRunRepository = subagentRunRepository;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "task_create",
                "백그라운드 태스크를 생성합니다. 장시간 실행되는 작업을 비동기로 실행할 때 사용합니다. " +
                        "태스크는 독립된 서브에이전트로 실행되며, task_get/task_list로 상태를 확인할 수 있습니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "description", Map.of("type", "string", "description", "태스크 설명"),
                                "prompt", Map.of("type", "string", "description", "서브에이전트에 전달할 프롬프트"),
                                "model", Map.of("type", "string", "description", "사용할 LLM 모델 (선택)"),
                                "isolation", Map.of("type", "string", "enum", List.of("worktree", "none"),
                                        "description", "격리 모드 (기본: none)")
                        ),
                        "required", List.of("description", "prompt")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return new ToolContractMeta(
                "task_create", "1.0", ToolScope.NATIVE,
                PermissionLevel.RESTRICTED_WRITE,
                false, false, false, true,
                RetryPolicy.NONE,
                List.of("task-management", "agent-thinking"),
                List.of("create", "task-management")
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String sessionId = ctx.sessionId();

        // BIZ-056: 동시 실행 태스크 5개 제한
        long runningCount = subagentRunRepository
                .findByParentSessionIdAndStatus(sessionId, "RUNNING").size();
        if (runningCount >= MAX_CONCURRENT_TASKS) {
            return ToolResult.denied("Maximum " + MAX_CONCURRENT_TASKS +
                    " concurrent tasks per session. Wait for running tasks to complete.");
        }

        String description = (String) input.get("description");
        String prompt = (String) input.get("prompt");
        String isolation = (String) input.getOrDefault("isolation", "none");

        // SubagentRunEntity를 Task로 생성
        SubagentRunEntity entity = new SubagentRunEntity();
        entity.setParentSessionId(sessionId);
        entity.setDescription(description);
        entity.setPrompt(prompt);
        entity.setStatus("RUNNING");
        entity.setRunInBackground(true);
        entity.setIsolationMode(isolation.toUpperCase());
        entity.setTaskDescription(description);
        entity.setPriority((String) input.getOrDefault("priority", "medium"));

        SubagentRunEntity saved = subagentRunRepository.save(entity);

        return ToolResult.ok(
                Map.of(
                        "task_id", saved.getId().toString(),
                        "status", "running",
                        "message", "Task created and running in background."
                ),
                "Task created: " + description
        );
    }
}
