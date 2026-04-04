package com.platform.api;

import com.platform.domain.WorkflowEntity;
import com.platform.domain.WorkflowRunEntity;
import com.platform.repository.WorkflowRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.workflow.WorkflowEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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

    public WorkflowController(WorkflowRepository workflowRepository,
                               WorkflowRunRepository workflowRunRepository,
                               WorkflowEngine workflowEngine) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowEngine = workflowEngine;
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
