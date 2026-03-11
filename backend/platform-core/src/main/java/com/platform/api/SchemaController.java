package com.platform.api;

import com.platform.domain.SchemaEntity;
import com.platform.domain.SchemaEntityId;
import com.platform.repository.SchemaRepository;
import com.platform.schema.SchemaValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/schemas")
@Tag(name = "Schemas", description = "JSON 스키마 관리")
public class SchemaController {

    private final SchemaRepository schemaRepository;
    private final SchemaValidator schemaValidator;

    public SchemaController(SchemaRepository schemaRepository, SchemaValidator schemaValidator) {
        this.schemaRepository = schemaRepository;
        this.schemaValidator = schemaValidator;
    }

    @GetMapping
    @Operation(summary = "스키마 목록 조회")
    public ApiResponse<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.page(schemaRepository.findAll(PageRequest.of(page, size)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "스키마 생성")
    public ApiResponse<SchemaEntity> create(@Valid @RequestBody SchemaRequest request) {
        SchemaEntityId pk = new SchemaEntityId(request.id(), request.version());
        if (schemaRepository.existsById(pk)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Schema already exists: " + request.id() + " v" + request.version());
        }
        SchemaEntity entity = new SchemaEntity();
        entity.setPk(pk);
        entity.setDomain(request.domain());
        entity.setDescription(request.description());
        entity.setJsonSchema(request.jsonSchema());
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
            @NotBlank String id,
            int version,
            String domain,
            String description,
            @NotNull Map<String, Object> jsonSchema
    ) {}
}
