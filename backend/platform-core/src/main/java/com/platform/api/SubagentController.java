package com.platform.api;

import com.platform.agent.AgentOrchestrator;
import com.platform.agent.SubagentLifecycleManager;
import com.platform.agent.SubagentRequest;
import com.platform.agent.SubagentResult;
import com.platform.agent.SubagentRunner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * CR-030 PRD-207~210: 서브에이전트 REST API.
 */
@RestController
@RequestMapping("/api/v1/agents")
@Tag(name = "Subagents", description = "서브에이전트 실행 및 관리")
public class SubagentController {

    private final SubagentRunner subagentRunner;
    private final AgentOrchestrator agentOrchestrator;
    private final SubagentLifecycleManager lifecycleManager;

    public SubagentController(SubagentRunner subagentRunner,
                              AgentOrchestrator agentOrchestrator,
                              SubagentLifecycleManager lifecycleManager) {
        this.subagentRunner = subagentRunner;
        this.agentOrchestrator = agentOrchestrator;
        this.lifecycleManager = lifecycleManager;
    }

    // ── 단일 에이전트 실행 ──

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "서브에이전트 실행", description = "단일 서브에이전트를 포그라운드/백그라운드로 실행한다.")
    public ApiResponse<SubagentResult> run(@Valid @RequestBody RunRequest request) {
        SubagentRequest agentRequest = new SubagentRequest(
                request.description(),
                request.prompt(),
                request.model(),
                request.connectionId(),
                request.isolation() != null
                        ? SubagentRequest.IsolationMode.valueOf(request.isolation().toUpperCase())
                        : SubagentRequest.IsolationMode.NONE,
                request.runInBackground(),
                request.timeoutMs() > 0 ? request.timeoutMs() : 120_000L,
                request.config(),
                request.parentSessionId()
        );
        SubagentResult result = subagentRunner.run(agentRequest);
        return ApiResponse.ok(result);
    }

    // ── 멀티에이전트 병렬 실행 ──

    @PostMapping("/orchestrate")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "멀티에이전트 조율 실행", description = "다수의 서브에이전트를 병렬 또는 순차로 실행한다.")
    public ApiResponse<AgentOrchestrator.OrchestratedResult> orchestrate(
            @Valid @RequestBody OrchestrateRequest request) {
        List<SubagentRequest> agentRequests = request.agents().stream()
                .map(a -> new SubagentRequest(
                        a.description(), a.prompt(), a.model(), a.connectionId(),
                        a.isolation() != null
                                ? SubagentRequest.IsolationMode.valueOf(a.isolation().toUpperCase())
                                : SubagentRequest.IsolationMode.NONE,
                        false, a.timeoutMs() > 0 ? a.timeoutMs() : 120_000L,
                        a.config(), request.parentSessionId()))
                .toList();

        AgentOrchestrator.OrchestratedResult result;
        if ("sequential".equalsIgnoreCase(request.execution())) {
            result = agentOrchestrator.runSequential(agentRequests);
        } else {
            result = agentOrchestrator.runParallel(agentRequests);
        }
        return ApiResponse.ok(result);
    }

    // ── 상태 조회 ──

    @GetMapping("/{runId}")
    @Operation(summary = "서브에이전트 실행 상태 조회")
    public ApiResponse<SubagentResult> getStatus(@PathVariable String runId) {
        try {
            return ApiResponse.ok(subagentRunner.getStatus(runId));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping("/session/{parentSessionId}")
    @Operation(summary = "부모 세션의 서브에이전트 실행 목록")
    public ApiResponse<List<SubagentResult>> listBySession(@PathVariable String parentSessionId) {
        return ApiResponse.ok(subagentRunner.listByParentSession(parentSessionId));
    }

    // ── 수명 주기 관리 ──

    @PostMapping("/{runId}/cancel")
    @Operation(summary = "서브에이전트 강제 취소")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable String runId) {
        boolean cancelled = lifecycleManager.cancel(runId);
        return ApiResponse.ok(Map.of("runId", runId, "cancelled", cancelled));
    }

    @GetMapping("/active")
    @Operation(summary = "활성 서브에이전트 목록")
    public ApiResponse<Map<String, Object>> getActive() {
        return ApiResponse.ok(Map.of(
                "activeCount", lifecycleManager.getActiveCount(),
                "agents", lifecycleManager.getActiveAgents().keySet()
        ));
    }

    // ── Request DTOs ──

    public record RunRequest(
            @NotBlank String description,
            @NotBlank String prompt,
            String model,
            String connectionId,
            String isolation,
            boolean runInBackground,
            long timeoutMs,
            Map<String, Object> config,
            String parentSessionId
    ) {}

    public record OrchestrateRequest(
            List<AgentConfig> agents,
            String execution,  // "parallel" | "sequential"
            String parentSessionId
    ) {}

    public record AgentConfig(
            @NotBlank String description,
            @NotBlank String prompt,
            String model,
            String connectionId,
            String isolation,
            long timeoutMs,
            Map<String, Object> config
    ) {}
}
