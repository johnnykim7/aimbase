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

import com.platform.tenant.ProjectContext;

import com.platform.auth.UserPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;

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
            @RequestParam(required = false) String domain,
            @RequestParam(required = false, name = "my") Boolean my
    ) {
        // CR-022: 사용자별 리소스 필터링
        if (Boolean.TRUE.equals(my)) {
            String userId = currentUserId();
            if (userId != null) return ApiResponse.ok(workflowRepository.findByCreatedByAndIsActiveTrue(userId));
        }
        // CR-021: 프로젝트 스코핑
        String projectId = ProjectContext.getProjectId();
        if (projectId != null) {
            return ApiResponse.ok(workflowRepository.findByProjectIdOrSharedAndIsActiveTrue(projectId));
        }
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
        entity.setId(request.id() != null && !request.id().isBlank() ? request.id() : java.util.UUID.randomUUID().toString());
        entity.setName(request.name());
        entity.setDomain(request.domain());
        entity.setTriggerConfig(request.triggerConfig());
        entity.setSteps(request.steps());
        entity.setErrorHandling(request.errorHandling());
        entity.setInputSchema(request.inputSchema());
        entity.setOutputSchema(request.outputSchema());
        // CR-021: X-Project-Id 헤더 또는 요청 body의 projectId
        String projectId = request.projectId() != null ? request.projectId() : ProjectContext.getProjectId();
        entity.setProjectId(projectId);
        entity.setActive(true);
        entity.setCreatedBy(currentUserId()); // CR-022
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
        entity.setInputSchema(request.inputSchema());
        entity.setOutputSchema(request.outputSchema());
        return ApiResponse.ok(workflowRepository.save(entity));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "워크플로우 삭제")
    @jakarta.transaction.Transactional
    public void delete(@PathVariable String id) {
        workflowRunRepository.deleteByWorkflowId(id);
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
            String id,
            @NotBlank String name,
            String domain,
            String projectId,
            @NotNull Map<String, Object> triggerConfig,
            @NotNull List<Map<String, Object>> steps,
            Map<String, Object> errorHandling,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema
    ) {}

    public record ApproveRequest(boolean approved, String reason) {}

    /** CR-022: SecurityContext에서 현재 사용자 ID 추출. API Key 인증(system-*)은 users FK 없으므로 null 반환 */
    private String currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal up) {
            String id = up.getId();
            if (id != null && id.startsWith("system-")) return null;
            return id;
        }
        return null;
    }
}
