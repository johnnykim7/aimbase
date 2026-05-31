package com.platform.api;

import com.platform.domain.WorkflowEntity;
import com.platform.domain.WorkflowRunEntity;
import com.platform.domain.WorkflowRunEventEntity;
import com.platform.repository.WorkflowRepository;
import com.platform.repository.WorkflowRunEventRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.workflow.WorkflowEngine;
import com.platform.workflow.WorkflowValidator;
import com.platform.workflow.event.WorkflowRunSubscriberRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflows")
@Tag(name = "Workflows", description = "워크플로우 관리")
public class WorkflowController {

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowEngine workflowEngine;
    private final WorkflowValidator workflowValidator;
    private final WorkflowRunSubscriberRegistry subscriberRegistry;
    private final WorkflowRunEventRepository eventRepository;

    public WorkflowController(WorkflowRepository workflowRepository,
                               WorkflowRunRepository workflowRunRepository,
                               WorkflowEngine workflowEngine,
                               WorkflowValidator workflowValidator,
                               WorkflowRunSubscriberRegistry subscriberRegistry,
                               WorkflowRunEventRepository eventRepository) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowEngine = workflowEngine;
        this.workflowValidator = workflowValidator;
        this.subscriberRegistry = subscriberRegistry;
        this.eventRepository = eventRepository;
    }

    @GetMapping
    @Operation(summary = "워크플로우 목록 조회")
    public ApiResponse<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String domain
    ) {
        var pageable = PageRequest.of(page, size);
        if (domain != null) {
            return ApiResponse.ok(workflowRepository.findByDomainAndIsActiveTrue(domain));
        }
        return ApiResponse.page(workflowRepository.findAll(pageable));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "워크플로우 생성")
    public ApiResponse<WorkflowEntity> create(@Valid @RequestBody WorkflowRequest request) {
        workflowValidator.validate(request.steps());
        WorkflowEntity entity = new WorkflowEntity();
        entity.setId(request.id());
        entity.setName(request.name());
        entity.setDomain(request.domain());
        entity.setTriggerConfig(request.triggerConfig());
        entity.setSteps(request.steps());
        entity.setErrorHandling(request.errorHandling());
        entity.setOutputSchema(request.outputSchema());
        entity.setInputSchema(request.inputSchema());
        entity.setActive(true);
        return ApiResponse.ok(workflowRepository.save(entity));
    }

    @GetMapping("/{id}")
    @Operation(summary = "워크플로우 상세 조회")
    public ApiResponse<WorkflowEntity> get(@PathVariable String id) {
        return workflowRepository.findById(id)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow not found: " + id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "워크플로우 수정")
    public ApiResponse<WorkflowEntity> update(@PathVariable String id,
                                               @Valid @RequestBody WorkflowRequest request) {
        workflowValidator.validate(request.steps());
        WorkflowEntity entity = workflowRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow not found: " + id));
        entity.setName(request.name());
        entity.setTriggerConfig(request.triggerConfig());
        entity.setSteps(request.steps());
        entity.setErrorHandling(request.errorHandling());
        entity.setOutputSchema(request.outputSchema());
        entity.setInputSchema(request.inputSchema());
        return ApiResponse.ok(workflowRepository.save(entity));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "워크플로우 삭제")
    public void delete(@PathVariable String id) {
        workflowRepository.deleteById(id);
    }

    @PostMapping("/{id}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "워크플로우 실행 — WorkflowEngine이 DAG를 비동기로 실행")
    public ApiResponse<WorkflowRunEntity> run(@PathVariable String id,
                                               @RequestBody(required = false) Map<String, Object> input) {
        // 존재 여부 확인 (404 반환)
        if (!workflowRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow not found: " + id);
        }
        WorkflowRunEntity run = workflowEngine.execute(id, input, null);
        return ApiResponse.ok(run);
    }

    @PostMapping("/runs/{runId}/approve")
    @Operation(summary = "HUMAN_INPUT 스텝 승인/거부")
    public ApiResponse<WorkflowRunEntity> approve(@PathVariable UUID runId,
                                                   @RequestBody ApproveRequest request) {
        try {
            WorkflowRunEntity run = workflowEngine.resume(runId, request.approved(), request.reason());
            return ApiResponse.ok(run);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/{id}/runs")
    @Operation(summary = "워크플로우 실행 이력 조회")
    public ApiResponse<?> runs(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.page(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(id, PageRequest.of(page, size)));
    }

    @GetMapping("/{id}/runs/{runId}")
    @Operation(summary = "워크플로우 실행 상세 조회")
    public ApiResponse<WorkflowRunEntity> getRun(@PathVariable String id,
                                                  @PathVariable UUID runId) {
        WorkflowRunEntity run = workflowRunRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow run not found: " + runId));
        if (!id.equals(run.getWorkflowId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run does not belong to workflow");
        }
        return ApiResponse.ok(run);
    }

    /**
     * CR-058: 워크플로우 실행 이벤트 SSE 구독.
     * 이벤트 4종 — workflow.snapshot / workflow.step / workflow.approval / workflow.done.
     */
    @GetMapping(value = "/runs/{runId}/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_workflow:subscribe') or isAuthenticated()")
    @Operation(summary = "워크플로우 실행 SSE 구독",
            description = "스텝 상태 전이 / 승인 대기 / 런 종료 이벤트를 실시간 스트리밍. 타임아웃 30분, 15초 heartbeat.")
    public SseEmitter subscribeRun(@PathVariable UUID runId) {
        WorkflowRunEntity run = workflowRunRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow run not found: " + runId));

        SseEmitter emitter = new SseEmitter(30L * 60L * 1000L); // 30분

        // 1) 연결 직후 현재 상태를 snapshot 으로 1회 전송.
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("run_id", run.getId().toString());
            snapshot.put("workflow_id", run.getWorkflowId());
            snapshot.put("session_id", run.getSessionId());
            snapshot.put("status", run.getStatus());
            snapshot.put("current_step", run.getCurrentStep());
            if (run.getParentRunId() != null) snapshot.put("parent_run_id", run.getParentRunId().toString());
            if (run.getParentStepId() != null) snapshot.put("parent_step_id", run.getParentStepId());
            if (run.getStepResults() != null) snapshot.put("step_results", run.getStepResults());
            if (run.getStartedAt() != null) snapshot.put("started_at", run.getStartedAt().toString());
            if (run.getCompletedAt() != null) snapshot.put("completed_at", run.getCompletedAt().toString());
            emitter.send(SseEmitter.event().name("workflow.snapshot").data(snapshot));
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        // 2) 런이 이미 종료 상태면 snapshot 만 보내고 바로 close.
        String status = run.getStatus();
        if ("completed".equals(status) || "failed".equals(status) || "cancelled".equals(status)) {
            try {
                emitter.send(SseEmitter.event().name("workflow.done")
                        .data(Map.of(
                                "run_id", run.getId().toString(),
                                "status", status,
                                "duration_ms", run.getCompletedAt() != null && run.getStartedAt() != null
                                        ? run.getCompletedAt().toInstant().toEpochMilli()
                                          - run.getStartedAt().toInstant().toEpochMilli()
                                        : 0L)));
            } catch (IOException ignored) {}
            emitter.complete();
            return emitter;
        }

        // 3) 이벤트 스트림 구독자로 등록.
        subscriberRegistry.register(run.getId(), emitter);
        return emitter;
    }

    /**
     * CR-090: 워크플로우 실행 이벤트 시간순 조회.
     *
     * <p>STEP_START / TOOL_USE / TOOL_RESULT / LLM_RESPONSE / STEP_END / STEP_FAILED
     * 6종 이벤트를 created_at, id ASC 로 반환. 소비앱이 "Claude Code 처럼 한 줄 흐름"
     * 화면을 사후 재구성할 때 사용.
     */
    @GetMapping("/runs/{runId}/events")
    @Operation(summary = "워크플로우 실행 이벤트 시간순 조회",
            description = "STEP_START/TOOL_USE/TOOL_RESULT/LLM_RESPONSE/STEP_END/STEP_FAILED 이벤트를 시간순으로 반환")
    public ApiResponse<List<Map<String, Object>>> getRunEvents(@PathVariable UUID runId) {
        // 존재 확인 (404 명시)
        workflowRunRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow run not found: " + runId));

        List<WorkflowRunEventEntity> events = eventRepository.findByRunIdOrderByCreatedAtAscIdAsc(runId);
        List<Map<String, Object>> body = events.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("event_type", e.getEventType() != null ? e.getEventType().name() : null);
            m.put("step_id", e.getStepId());
            m.put("iteration", e.getIteration());
            m.put("tool_name", e.getToolName());
            m.put("duration_ms", e.getDurationMs());
            m.put("payload", e.getPayload());
            m.put("trace_id", e.getTraceId());
            m.put("subagent_run_id", e.getSubagentRunId() != null ? e.getSubagentRunId().toString() : null);
            m.put("created_at", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
            return m;
        }).toList();
        return ApiResponse.ok(body);
    }

    public record WorkflowRequest(
            @NotBlank String id,
            @NotBlank String name,
            String domain,
            @NotNull Map<String, Object> triggerConfig,
            @NotNull List<Map<String, Object>> steps,
            Map<String, Object> errorHandling,
            Map<String, Object> outputSchema,
            Map<String, Object> inputSchema
    ) {}

    public record ApproveRequest(boolean approved, String reason) {}
}
