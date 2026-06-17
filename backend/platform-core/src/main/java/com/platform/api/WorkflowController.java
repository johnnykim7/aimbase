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

    // CR-105: 워크플로우 실행 중지 (협조적)
    @PostMapping("/runs/{runId}/cancel")
    @Operation(summary = "워크플로우 실행 중지 (협조적)",
            description = "진행 중인 run 을 협조적으로 중지한다. running 은 다음 스텝 경계에서 멈추고, "
                    + "pending_approval 은 즉시 cancelled 로 전이한다. 이미 종료된 run 은 변경 없이 반환.")
    public ApiResponse<WorkflowRunEntity> cancel(@PathVariable UUID runId) {
        try {
            WorkflowRunEntity run = workflowEngine.cancelRun(runId);
            return ApiResponse.ok(run);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
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

    /**
     * CR-102: 전체 워크플로우 횡단 실행 이력 조회.
     * 특정 워크플로우에 얽매이지 않고 최근 run 을 한눈에 보는 목록 — FE "실행 내역" 화면의 받침.
     */
    @GetMapping("/runs")
    @Operation(summary = "전체 워크플로우 실행 이력 조회 (횡단)",
            description = "모든 워크플로우의 run 을 started_at DESC 로 반환. workflow_id / status 필터 옵션.")
    public ApiResponse<?> allRuns(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(name = "workflow_id", required = false) String workflowId,
            @RequestParam(required = false) String status
    ) {
        return ApiResponse.page(workflowRunRepository.searchRuns(workflowId, status, PageRequest.of(page, size)));
    }

    /** CR-102: run 단건 조회 (워크플로우 id 없이) — 횡단 실행 내역 화면에서 상세 진입용. */
    @GetMapping("/runs/{runId}")
    @Operation(summary = "워크플로우 실행 단건 조회 (횡단)",
            description = "기존 run 필드 + 현재 스텝의 사람이 읽는 이름(currentStepName)과 전체 스텝 진행 목록(steps)을 함께 반환.")
    public ApiResponse<WorkflowRunDetail> getRunById(@PathVariable UUID runId) {
        WorkflowRunEntity run = workflowRunRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow run not found: " + runId));
        return ApiResponse.ok(toRunDetail(run));
    }

    @GetMapping("/{id}/runs/{runId}")
    @Operation(summary = "워크플로우 실행 상세 조회",
            description = "기존 run 필드 + 현재 스텝의 사람이 읽는 이름(currentStepName)과 전체 스텝 진행 목록(steps)을 함께 반환.")
    public ApiResponse<WorkflowRunDetail> getRun(@PathVariable String id,
                                                  @PathVariable UUID runId) {
        WorkflowRunEntity run = workflowRunRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow run not found: " + runId));
        if (!id.equals(run.getWorkflowId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run does not belong to workflow");
        }
        return ApiResponse.ok(toRunDetail(run));
    }

    /**
     * run 엔티티에 WF 정의의 사람이 읽는 스텝 이름을 덧붙여 응답 DTO 로 조립.
     *
     * <p>{@code currentStepName} = 현재 스텝 id 에 해당하는 정의 step 의 {@code name}.
     * {@code steps[]} = 정의 순서대로 {@code {id, name, status}} — 진행바용.
     * status 도출: stepResults 에 키 존재 → completed / currentStep 일치 시 run 이 terminal 이면
     * 그 상태, 아니면 running / 그 외 pending. WF 정의를 못 찾으면 두 필드는 null/빈 목록으로 graceful.
     */
    private WorkflowRunDetail toRunDetail(WorkflowRunEntity run) {
        List<Map<String, Object>> defSteps = workflowRepository.findById(run.getWorkflowId())
                .map(WorkflowEntity::getSteps)
                .orElse(List.of());

        String currentStep = run.getCurrentStep();
        String status = run.getStatus();
        Map<String, Object> stepResults = run.getStepResults() != null ? run.getStepResults() : Map.of();
        boolean terminal = "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);

        String currentStepName = null;
        List<WorkflowRunDetail.StepProgress> steps = new java.util.ArrayList<>();
        for (Map<String, Object> s : defSteps) {
            String sid = s.get("id") != null ? s.get("id").toString() : null;
            String sname = s.get("name") != null ? s.get("name").toString() : sid;
            if (sid != null && sid.equals(currentStep)) {
                currentStepName = sname;
            }
            String stepStatus;
            if (sid != null && stepResults.containsKey(sid)) {
                stepStatus = "completed";
            } else if (sid != null && sid.equals(currentStep)) {
                stepStatus = terminal ? status : "running";
            } else {
                stepStatus = "pending";
            }
            steps.add(new WorkflowRunDetail.StepProgress(sid, sname, stepStatus));
        }

        return new WorkflowRunDetail(
                run.getId(), run.getWorkflowId(), run.getSessionId(), status,
                currentStep, currentStepName, steps,
                run.getStepResults(), run.getInputData(), run.getError(),
                run.getStartedAt(), run.getCompletedAt(),
                run.getParentRunId(), run.getParentStepId());
    }

    /**
     * CR-065: 부모 run 의 자식 서브워크플로우 run 트리 조회.
     * SUB_WORKFLOW 스텝이 생성한 자식 run 들을 started_at 오름차순으로 반환한다.
     */
    @GetMapping("/runs/{runId}/children")
    @Operation(summary = "서브워크플로우 자식 실행 트리 조회")
    public ApiResponse<List<WorkflowRunEntity>> children(@PathVariable UUID runId) {
        if (!workflowRunRepository.existsById(runId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow run not found: " + runId);
        }
        return ApiResponse.ok(workflowRunRepository.findByParentRunIdOrderByStartedAtAsc(runId));
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
            description = "STEP_START/TOOL_USE/TOOL_RESULT/LLM_RESPONSE/STEP_END/STEP_FAILED 이벤트를 시간순으로 반환. "
                    + "include_body=true 시 품질 분석용 본문 전문(prompt/response/input/output)을 함께 반환 (CR-102).")
    public ApiResponse<List<Map<String, Object>>> getRunEvents(
            @PathVariable UUID runId,
            @RequestParam(name = "include_body", defaultValue = "false") boolean includeBody) {
        // 존재 확인 (404 명시)
        workflowRunRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow run not found: " + runId));

        List<WorkflowRunEventEntity> events = eventRepository.findByRunIdOrderByCreatedAtAscIdAsc(runId);
        return ApiResponse.ok(events.stream().map(e -> toEventMap(e, includeBody)).toList());
    }

    /**
     * CR-102: 이벤트 단건 본문 조회.
     * 타임라인은 메타로 가볍게 띄우고, 행을 펼칠 때만 해당 이벤트의 본문 전문을 가져온다
     * (run 1건 include_body=true 일괄 응답은 LLM 프롬프트 전문 × 수십 건이라 수 MB 가 될 수 있음).
     */
    @GetMapping("/runs/{runId}/events/{eventId}")
    @Operation(summary = "워크플로우 실행 이벤트 단건 조회 (본문 전문 포함)",
            description = "prompt_text/response_text/input_json/output_text 본문 전문을 항상 포함해 반환.")
    public ApiResponse<Map<String, Object>> getRunEvent(@PathVariable UUID runId,
                                                        @PathVariable Long eventId) {
        WorkflowRunEventEntity event = eventRepository.findById(eventId)
                .filter(e -> runId.equals(e.getRunId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow run event not found: " + eventId));
        return ApiResponse.ok(toEventMap(event, true));
    }

    private Map<String, Object> toEventMap(WorkflowRunEventEntity e, boolean includeBody) {
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
        // CR-102: 품질 분석 본문 전문 — 옵트인(타임라인은 가볍게, 정독 시에만 본문 동반)
        if (includeBody) {
            m.put("prompt_text", e.getPromptText());
            m.put("response_text", e.getResponseText());
            m.put("input_json", e.getInputJson());
            m.put("output_text", e.getOutputText());
        }
        return m;
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

    /**
     * run 단건 조회 응답 — 기존 run 필드를 그대로 미러링(하위호환)하면서
     * 사람이 읽는 현재 스텝 이름과 전체 스텝 진행 목록을 덧붙인다.
     */
    public record WorkflowRunDetail(
            UUID id,
            String workflowId,
            String sessionId,
            String status,
            String currentStep,
            String currentStepName,
            List<StepProgress> steps,
            Map<String, Object> stepResults,
            Map<String, Object> inputData,
            Map<String, Object> error,
            java.time.OffsetDateTime startedAt,
            java.time.OffsetDateTime completedAt,
            UUID parentRunId,
            String parentStepId
    ) {
        /** 진행바용 스텝 1건. status = completed | running | pending | failed | cancelled. */
        public record StepProgress(String id, String name, String status) {}
    }
}
