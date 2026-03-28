package com.platform.api;

import com.platform.action.model.ActionRequest;
import com.platform.action.model.ActionTarget;
import com.platform.domain.PolicyEntity;
import com.platform.policy.PolicyEngine;
import com.platform.repository.PolicyRepository;
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

@RestController
@RequestMapping("/api/v1/policies")
@Tag(name = "Policies", description = "정책 관리")
public class PolicyController {

    private final PolicyRepository policyRepository;
    private final PolicyEngine policyEngine;

    public PolicyController(PolicyRepository policyRepository, PolicyEngine policyEngine) {
        this.policyRepository = policyRepository;
        this.policyEngine = policyEngine;
    }

    @GetMapping
    @Operation(summary = "정책 목록 조회")
    public ApiResponse<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String domain
    ) {
        var pageable = PageRequest.of(page, size);
        if (domain != null) {
            return ApiResponse.ok(policyRepository.findByDomainAndIsActiveTrue(domain));
        }
        return ApiResponse.page(policyRepository.findAll(pageable));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "정책 생성")
    public ApiResponse<PolicyEntity> create(@Valid @RequestBody PolicyRequest request) {
        PolicyEntity entity = new PolicyEntity();
        entity.setId(request.id() != null && !request.id().isBlank()
                ? request.id()
                : java.util.UUID.randomUUID().toString());
        entity.setName(request.name());
        entity.setDomain(request.domain());
        entity.setPriority(request.priority() != null ? request.priority() : 0);
        entity.setMatchRules(request.matchRules());
        entity.setRules(request.rules());
        entity.setActive(true);
        return ApiResponse.ok(policyRepository.save(entity));
    }

    @GetMapping("/{id}")
    @Operation(summary = "정책 상세 조회")
    public ApiResponse<PolicyEntity> get(@PathVariable String id) {
        return policyRepository.findById(id)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Policy not found: " + id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "정책 수정")
    public ApiResponse<PolicyEntity> update(@PathVariable String id,
                                             @Valid @RequestBody PolicyRequest request) {
        PolicyEntity entity = policyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Policy not found: " + id));
        entity.setName(request.name());
        entity.setDomain(request.domain());
        if (request.priority() != null) entity.setPriority(request.priority());
        entity.setMatchRules(request.matchRules());
        entity.setRules(request.rules());
        return ApiResponse.ok(policyRepository.save(entity));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "정책 삭제")
    public void delete(@PathVariable String id) {
        policyRepository.deleteById(id);
    }

    @PatchMapping("/{id}/activate")
    @Operation(summary = "정책 활성화/비활성화")
    public ApiResponse<PolicyEntity> setActive(@PathVariable String id,
                                                @RequestParam boolean active) {
        PolicyEntity entity = policyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Policy not found: " + id));
        entity.setActive(active);
        return ApiResponse.ok(policyRepository.save(entity));
    }

    @PostMapping("/simulate")
    @Operation(summary = "정책 시뮬레이션")
    public ApiResponse<Map<String, Object>> simulate(@RequestBody SimulateRequest request) {
        String adapter = request.adapter() != null ? request.adapter() : "unknown";
        ActionRequest actionRequest = ActionRequest.of(
                request.intent(),
                ActionRequest.ActionType.NOTIFY,
                List.of(new ActionTarget(adapter, null, null)),
                Map.of()
        );
        var result = policyEngine.evaluate(actionRequest);
        return ApiResponse.ok(Map.of(
                "action", result.action().name(),
                "triggered", result.triggeredPolicies().size(),
                "denyReason", result.denialReason() != null ? result.denialReason() : "",
                "requiresApproval", result.action() == com.platform.policy.model.PolicyResult.PolicyAction.REQUIRE_APPROVAL
        ));
    }

    public record PolicyRequest(
            String id,
            @NotBlank String name,
            String domain,
            Integer priority,
            @NotNull Map<String, Object> matchRules,
            @NotNull List<Map<String, Object>> rules
    ) {}

    public record SimulateRequest(
            @NotBlank String intent,
            String adapter,
            String sessionId
    ) {}
}
