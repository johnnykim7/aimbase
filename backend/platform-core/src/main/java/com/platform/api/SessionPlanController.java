package com.platform.api;

import com.platform.agent.PlanService;
import com.platform.agent.TodoService;
import com.platform.domain.SubagentRunEntity;
import com.platform.domain.TodoEntity;
import com.platform.repository.SubagentRunRepository;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-033 FE-015: Plan/Todo/Task FE 조회 REST API.
 * 세션별 Plan, Todo, Task 데이터를 FE 대시보드에 제공한다.
 */
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}")
public class SessionPlanController {

    private final PlanService planService;
    private final TodoService todoService;
    private final SubagentRunRepository subagentRunRepository;

    public SessionPlanController(PlanService planService,
                                 TodoService todoService,
                                 SubagentRunRepository subagentRunRepository) {
        this.planService = planService;
        this.todoService = todoService;
        this.subagentRunRepository = subagentRunRepository;
    }

    @GetMapping("/plan")
    public ApiResponse<Map<String, Object>> getPlan(@PathVariable String sessionId) {
        return planService.getLatestPlan(sessionId)
                .map(plan -> {
                    Map<String, Object> data = new HashMap<>();
                    data.put("plan_id", plan.getId().toString());
                    data.put("title", plan.getTitle());
                    data.put("status", plan.getStatus().name());
                    data.put("goals", plan.getGoals());
                    data.put("steps", plan.getSteps());
                    data.put("constraints", plan.getPlanConstraints());
                    data.put("verification_result", plan.getVerificationResult());
                    data.put("created_at", plan.getCreatedAt().toString());
                    data.put("updated_at", plan.getUpdatedAt().toString());
                    return ApiResponse.ok(data);
                })
                .orElse(ApiResponse.ok(null));
    }

    @GetMapping("/todos")
    public ApiResponse<List<Map<String, Object>>> getTodos(@PathVariable String sessionId) {
        List<Map<String, Object>> todos = todoService.getTodos(sessionId).stream()
                .map(TodoEntity::toMap)
                .toList();
        return ApiResponse.ok(todos);
    }

    @GetMapping("/tasks")
    public ApiResponse<List<Map<String, Object>>> getTasks(@PathVariable String sessionId) {
        List<Map<String, Object>> tasks = subagentRunRepository
                .findByParentSessionIdOrderByStartedAtDesc(sessionId).stream()
                .map(this::toTaskMap)
                .toList();
        return ApiResponse.ok(tasks);
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<Map<String, Object>> getTask(@PathVariable String sessionId,
                                                     @PathVariable String taskId) {
        return subagentRunRepository.findById(java.util.UUID.fromString(taskId))
                .map(entity -> {
                    Map<String, Object> data = toTaskMap(entity);
                    data.put("output", entity.getOutput());
                    data.put("large_output", entity.getLargeOutput());
                    data.put("error", entity.getError());
                    data.put("prompt", entity.getPrompt());
                    return ApiResponse.ok(data);
                })
                .orElse(ApiResponse.error("Task not found"));
    }

    private Map<String, Object> toTaskMap(SubagentRunEntity e) {
        Map<String, Object> m = new HashMap<>();
        m.put("task_id", e.getId().toString());
        m.put("status", e.getStatus().toLowerCase());
        m.put("description", e.getTaskDescription() != null ? e.getTaskDescription() : e.getDescription());
        m.put("priority", e.getPriority());
        m.put("duration_ms", e.getDurationMs());
        m.put("token_usage", Map.of(
                "input_tokens", e.getInputTokens(),
                "output_tokens", e.getOutputTokens()
        ));
        m.put("created_at", e.getStartedAt() != null ? e.getStartedAt().toString() : null);
        m.put("completed_at", e.getCompletedAt() != null ? e.getCompletedAt().toString() : null);
        return m;
    }
}
