package com.platform.tool.builtin;

import com.platform.agent.AgentType;
import com.platform.agent.SubagentRequest;
import com.platform.agent.SubagentResult;
import com.platform.agent.SubagentRunner;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookEvent;
import com.platform.hook.HookInput;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.repository.SubagentRunRepository;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-033 PRD-226: 백그라운드 태스크 생성.
 * CR-053 Phase 0.5: SubagentRunner.run()을 호출해 실제 실행까지 연결.
 * BIZ-056: 세션당 동시 실행 태스크 5개 제한.
 */
@Component
public class TaskCreateTool implements EnhancedToolExecutor {

    private static final int MAX_CONCURRENT_TASKS = 5;

    private final SubagentRunRepository subagentRunRepository;
    private final SubagentRunner subagentRunner;
    private final HookDispatcher hookDispatcher;

    public TaskCreateTool(SubagentRunRepository subagentRunRepository,
                          SubagentRunner subagentRunner,
                          HookDispatcher hookDispatcher) {
        this.subagentRunRepository = subagentRunRepository;
        this.subagentRunner = subagentRunner;
        this.hookDispatcher = hookDispatcher;
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
        String model = (String) input.get("model");
        String isolationRaw = (String) input.getOrDefault("isolation", "none");
        SubagentRequest.IsolationMode isolation = "worktree".equalsIgnoreCase(isolationRaw)
                ? SubagentRequest.IsolationMode.WORKTREE
                : SubagentRequest.IsolationMode.NONE;

        // SubagentRunner에 위임 — DB 저장/Virtual Thread 실행/훅 발행을 Runner가 통합 처리
        SubagentRequest request = new SubagentRequest(
                description, prompt, model,
                null,                 // connectionId: 부모 세션 커넥션 상속
                isolation,
                true,                 // runInBackground
                0L,                   // timeoutMs: 기본값(120s)
                Map.of(),
                sessionId,
                AgentType.GENERAL
        );

        SubagentResult result = subagentRunner.run(request);
        String taskId = result.subagentRunId();

        // CR-034: TASK_CREATED 훅 발행
        try {
            hookDispatcher.dispatch(HookEvent.TASK_CREATED,
                    HookInput.of(HookEvent.TASK_CREATED, sessionId,
                            Map.of("taskId", taskId,
                                    "description", description),
                            Map.of()));
        } catch (Exception e) {
            // 훅 실패가 태스크 생성을 막지 않음
        }

        return ToolResult.ok(
                Map.of(
                        "task_id", taskId,
                        "status", "running",
                        "message", "Task created and running in background."
                ),
                "Task created: " + description
        );
    }
}
