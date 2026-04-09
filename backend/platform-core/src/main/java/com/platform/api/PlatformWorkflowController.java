package com.platform.api;

import com.platform.domain.WorkflowRunEntity;
import com.platform.domain.master.PlatformWorkflowEntity;
import com.platform.repository.master.PlatformWorkflowRepository;
import com.platform.workflow.WorkflowEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 플랫폼 공용 워크플로우 관리 + 실행.
 * Master DB에 저장되며 모든 테넌트가 실행 가능.
 */
@RestController
@RequestMapping("/api/v1/platform/workflows")
@Tag(name = "Platform Workflows", description = "플랫폼 공용 워크플로우 관리")
public class PlatformWorkflowController {

    private final PlatformWorkflowRepository repository;
    private final WorkflowEngine workflowEngine;

    public PlatformWorkflowController(PlatformWorkflowRepository repository,
                                       WorkflowEngine workflowEngine) {
        this.repository = repository;
        this.workflowEngine = workflowEngine;
    }

    @GetMapping
    @Operation(summary = "공용 워크플로우 목록 조회")
    public ApiResponse<List<PlatformWorkflowEntity>> list(
            @RequestParam(required = false) String category) {
        if (category != null && !category.isBlank()) {
            return ApiResponse.ok(repository.findByCategoryAndIsActiveTrueOrderByName(category));
        }
        return ApiResponse.ok(repository.findByIsActiveTrueOrderByName());
    }

    @GetMapping("/{id}")
    @Operation(summary = "공용 워크플로우 상세 조회")
    public ApiResponse<PlatformWorkflowEntity> get(@PathVariable String id) {
        return repository.findById(id)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Platform workflow not found: " + id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "공용 워크플로우 등록")
    public ApiResponse<PlatformWorkflowEntity> create(@Valid @RequestBody PlatformWorkflowRequest request) {
        PlatformWorkflowEntity entity = new PlatformWorkflowEntity();
        entity.setId(request.id());
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setCategory(request.category());
        entity.setTriggerConfig(request.triggerConfig());
        entity.setSteps(request.steps());
        entity.setErrorHandling(request.errorHandling());
        entity.setOutputSchema(request.outputSchema());
        entity.setInputSchema(request.inputSchema());
        entity.setActive(true);
        return ApiResponse.ok(repository.save(entity));
    }

    @PutMapping("/{id}")
    @Operation(summary = "공용 워크플로우 수정")
    public ApiResponse<PlatformWorkflowEntity> update(@PathVariable String id,
                                                       @Valid @RequestBody PlatformWorkflowRequest request) {
        PlatformWorkflowEntity entity = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Platform workflow not found: " + id));
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setCategory(request.category());
        entity.setTriggerConfig(request.triggerConfig());
        entity.setSteps(request.steps());
        entity.setErrorHandling(request.errorHandling());
        entity.setOutputSchema(request.outputSchema());
        entity.setInputSchema(request.inputSchema());
        return ApiResponse.ok(repository.save(entity));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "공용 워크플로우 삭제")
    public void delete(@PathVariable String id) {
        repository.deleteById(id);
    }

    @PostMapping("/{id}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "공용 워크플로우 실행 — 현재 테넌트 컨텍스트에서 DAG 비동기 실행")
    public ApiResponse<WorkflowRunEntity> run(@PathVariable String id,
                                               @RequestBody(required = false) Map<String, Object> input) {
        PlatformWorkflowEntity workflow = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Platform workflow not found: " + id));

        if (!workflow.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Platform workflow is not active: " + id);
        }

        WorkflowRunEntity run = workflowEngine.executePlatform(workflow, input, null);
        return ApiResponse.ok(run);
    }

    public record PlatformWorkflowRequest(
            @NotBlank String id,
            @NotBlank String name,
            String description,
            String category,
            @NotNull Map<String, Object> triggerConfig,
            @NotNull List<Map<String, Object>> steps,
            Map<String, Object> errorHandling,
            Map<String, Object> outputSchema,
            Map<String, Object> inputSchema
    ) {}
}
