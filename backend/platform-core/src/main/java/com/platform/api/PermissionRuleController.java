package com.platform.api;

import com.platform.domain.PermissionRuleEntity;
import com.platform.repository.PermissionRuleRepository;
import com.platform.tool.PermissionLevel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * CR-030 PRD-197: 권한 규칙 관리 API.
 *
 * 도구명 패턴 → 최소 PermissionLevel 매핑 규칙을 CRUD.
 * PermissionClassifier가 AUTO 모드에서 이 규칙을 참조.
 */
@RestController
@RequestMapping("/api/v1/permission-rules")
@Tag(name = "Permission Rules", description = "도구 권한 규칙 관리")
public class PermissionRuleController {

    private final PermissionRuleRepository ruleRepository;

    public PermissionRuleController(PermissionRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @GetMapping
    @Operation(summary = "권한 규칙 목록 조회")
    public ApiResponse<List<PermissionRuleEntity>> list() {
        return ApiResponse.ok(ruleRepository.findByIsActiveTrueOrderByPriorityDesc());
    }

    @GetMapping("/all")
    @Operation(summary = "전체 권한 규칙 조회 (비활성 포함)")
    public ApiResponse<List<PermissionRuleEntity>> listAll() {
        return ApiResponse.ok(ruleRepository.findAll());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "권한 규칙 생성")
    public ApiResponse<PermissionRuleEntity> create(@Valid @RequestBody RuleRequest request) {
        validateLevel(request.requiredLevel());

        PermissionRuleEntity entity = new PermissionRuleEntity();
        entity.setId(request.id() != null && !request.id().isBlank()
                ? request.id()
                : "perm-rule-" + UUID.randomUUID().toString().substring(0, 8));
        entity.setName(request.name());
        entity.setToolNamePattern(request.toolNamePattern());
        entity.setRequiredLevel(request.requiredLevel());
        entity.setPriority(request.priority() != null ? request.priority() : 0);
        entity.setDescription(request.description());
        entity.setActive(true);
        return ApiResponse.ok(ruleRepository.save(entity));
    }

    @GetMapping("/{id}")
    @Operation(summary = "권한 규칙 상세 조회")
    public ApiResponse<PermissionRuleEntity> get(@PathVariable String id) {
        return ruleRepository.findById(id)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Permission rule not found: " + id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "권한 규칙 수정")
    public ApiResponse<PermissionRuleEntity> update(@PathVariable String id,
                                                     @Valid @RequestBody RuleRequest request) {
        validateLevel(request.requiredLevel());

        PermissionRuleEntity entity = ruleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Permission rule not found: " + id));
        entity.setName(request.name());
        entity.setToolNamePattern(request.toolNamePattern());
        entity.setRequiredLevel(request.requiredLevel());
        if (request.priority() != null) entity.setPriority(request.priority());
        entity.setDescription(request.description());
        return ApiResponse.ok(ruleRepository.save(entity));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "권한 규칙 삭제")
    public void delete(@PathVariable String id) {
        ruleRepository.deleteById(id);
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "권한 규칙 활성화/비활성화")
    public ApiResponse<PermissionRuleEntity> setActive(@PathVariable String id,
                                                        @RequestParam(defaultValue = "true") boolean active) {
        PermissionRuleEntity entity = ruleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Permission rule not found: " + id));
        entity.setActive(active);
        return ApiResponse.ok(ruleRepository.save(entity));
    }

    private void validateLevel(String level) {
        try {
            PermissionLevel parsed = PermissionLevel.valueOf(level);
            if (parsed == PermissionLevel.AUTO) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "AUTO cannot be used as required_level — it is a classification mode, not a concrete level");
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid permission level: " + level
                            + ". Valid values: READ_ONLY, RESTRICTED_WRITE, FULL");
        }
    }

    public record RuleRequest(
            String id,
            @NotBlank String name,
            @NotBlank String toolNamePattern,
            @NotNull String requiredLevel,
            Integer priority,
            String description
    ) {}
}
