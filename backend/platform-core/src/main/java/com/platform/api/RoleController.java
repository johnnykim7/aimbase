package com.platform.api;

import com.platform.domain.RoleEntity;
import com.platform.repository.RoleRepository;
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
@RequestMapping("/api/v1/roles")
@Tag(name = "Roles", description = "역할 관리")
public class RoleController {

    private final RoleRepository roleRepository;

    public RoleController(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @GetMapping
    @Operation(summary = "역할 목록 조회")
    public ApiResponse<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.page(roleRepository.findAll(PageRequest.of(page, size)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "역할 생성")
    public ApiResponse<RoleEntity> create(@Valid @RequestBody RoleRequest request) {
        if (roleRepository.existsById(request.id())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Role already exists: " + request.id());
        }
        RoleEntity entity = new RoleEntity();
        entity.setId(request.id());
        entity.setName(request.name());
        entity.setPermissions(request.permissions());
        return ApiResponse.ok(roleRepository.save(entity));
    }

    @GetMapping("/{id}")
    @Operation(summary = "역할 상세 조회")
    public ApiResponse<RoleEntity> get(@PathVariable String id) {
        return roleRepository.findById(id)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Role not found: " + id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "역할 수정")
    public ApiResponse<RoleEntity> update(@PathVariable String id,
                                           @Valid @RequestBody RoleRequest request) {
        RoleEntity entity = roleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Role not found: " + id));
        entity.setName(request.name());
        entity.setPermissions(request.permissions());
        return ApiResponse.ok(roleRepository.save(entity));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "역할 삭제")
    public void delete(@PathVariable String id) {
        roleRepository.deleteById(id);
    }

    public record RoleRequest(
            @NotBlank String id,
            @NotBlank String name,
            @NotNull Map<String, Object> permissions
    ) {}
}
