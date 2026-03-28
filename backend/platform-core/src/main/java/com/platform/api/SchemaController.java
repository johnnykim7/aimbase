package com.platform.api;

import com.platform.domain.SchemaEntity;
import com.platform.domain.SchemaEntityId;
import com.platform.repository.ProjectResourceRepository;
import com.platform.repository.SchemaRepository;
import com.platform.schema.SchemaValidator;
import com.platform.tenant.ProjectContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.platform.auth.UserPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/schemas")
@Tag(name = "Schemas", description = "JSON 스키마 관리")
public class SchemaController {

    private final SchemaRepository schemaRepository;
    private final SchemaValidator schemaValidator;
    private final ProjectResourceRepository projectResourceRepository;

    public SchemaController(SchemaRepository schemaRepository, SchemaValidator schemaValidator,
                            ProjectResourceRepository projectResourceRepository) {
        this.schemaRepository = schemaRepository;
        this.schemaValidator = schemaValidator;
        this.projectResourceRepository = projectResourceRepository;
    }

    @GetMapping
    @Operation(summary = "스키마 목록 조회")
    public ApiResponse<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false, name = "my") Boolean my
    ) {
        // CR-022: 사용자별 리소스 필터링
        if (Boolean.TRUE.equals(my)) {
            String userId = currentUserId();
            if (userId != null) return ApiResponse.ok(schemaRepository.findByCreatedBy(userId));
        }
        // CR-021: 프로젝트 스코핑
        String projectId = ProjectContext.getProjectId();
        if (projectId != null) {
            List<String> ids = projectResourceRepository.findResourceIdsByProjectIdAndResourceType(projectId, "schema");
            if (ids.isEmpty()) return ApiResponse.ok(List.of());
            return ApiResponse.ok(schemaRepository.findByIdIn(ids));
        }
        return ApiResponse.page(schemaRepository.findAll(PageRequest.of(page, size)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "스키마 생성")
    public ApiResponse<SchemaEntity> create(@Valid @RequestBody SchemaRequest request) {
        String resolvedId = request.id() != null && !request.id().isBlank() ? request.id() : java.util.UUID.randomUUID().toString();
        SchemaEntityId pk = new SchemaEntityId(resolvedId, request.version());
        if (schemaRepository.existsById(pk)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Schema already exists: " + request.id() + " v" + request.version());
        }
        SchemaEntity entity = new SchemaEntity();
        entity.setPk(pk);
        entity.setDomain(request.domain());
        entity.setDescription(request.description());
        entity.setJsonSchema(request.jsonSchema());
        entity.setCreatedBy(currentUserId()); // CR-022
        return ApiResponse.ok(schemaRepository.save(entity));
    }

    @GetMapping("/{id}/{version}")
    @Operation(summary = "스키마 상세 조회")
    public ApiResponse<SchemaEntity> get(@PathVariable String id, @PathVariable int version) {
        SchemaEntityId pk = new SchemaEntityId(id, version);
        return schemaRepository.findById(pk)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Schema not found: " + id + " v" + version));
    }

    @DeleteMapping("/{id}/{version}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "스키마 삭제")
    public void delete(@PathVariable String id, @PathVariable int version) {
        schemaRepository.deleteById(new SchemaEntityId(id, version));
    }

    @PostMapping("/{id}/{version}/validate")
    @Operation(summary = "데이터 유효성 검증")
    public ApiResponse<Map<String, Object>> validate(
            @PathVariable String id,
            @PathVariable int version,
            @RequestBody Map<String, Object> data
    ) {
        SchemaEntityId pk = new SchemaEntityId(id, version);
        SchemaEntity schema = schemaRepository.findById(pk)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Schema not found: " + id + " v" + version));

        var result = schemaValidator.validate(schema.getJsonSchema(), data);
        return ApiResponse.ok(Map.of(
                "valid", result.valid(),
                "errors", result.errors()
        ));
    }

    public record SchemaRequest(
            String id,
            int version,
            String domain,
            String description,
            @NotNull Map<String, Object> jsonSchema
    ) {}

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
