package com.platform.api;

import com.platform.domain.ContextRecipeEntity;
import com.platform.repository.ContextRecipeRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CR-029: Context Recipe CRUD API.
 */
@RestController
@RequestMapping("/api/v1/context-recipes")
@Tag(name = "Context Recipe", description = "컨텍스트 조립 레시피 (CR-029)")
public class ContextRecipeController {

    private final ContextRecipeRepository repository;

    public ContextRecipeController(ContextRecipeRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "Context Recipe 목록 조회")
    public ApiResponse<List<ContextRecipeEntity>> list(
            @RequestParam(required = false) String domain_app) {
        List<ContextRecipeEntity> recipes = domain_app != null
                ? repository.findByDomainAppAndActiveTrue(domain_app)
                : repository.findByActiveTrue();
        return ApiResponse.ok(recipes);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Context Recipe 상세 조회")
    public ApiResponse<ContextRecipeEntity> get(@PathVariable String id) {
        return repository.findById(id)
                .map(ApiResponse::ok)
                .orElse(ApiResponse.error("레시피를 찾을 수 없습니다: " + id));
    }

    @PostMapping
    @Operation(summary = "Context Recipe 생성")
    public ApiResponse<ContextRecipeEntity> create(@RequestBody ContextRecipeEntity entity) {
        if (entity.getId() == null || entity.getId().isBlank()) {
            entity.setId("recipe-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        }
        return ApiResponse.ok(repository.save(entity));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Context Recipe 수정")
    public ApiResponse<ContextRecipeEntity> update(@PathVariable String id,
                                                    @RequestBody ContextRecipeEntity entity) {
        return repository.findById(id)
                .map(existing -> {
                    existing.setName(entity.getName());
                    existing.setDescription(entity.getDescription());
                    existing.setRecipe(entity.getRecipe());
                    existing.setDomainApp(entity.getDomainApp());
                    existing.setScopeType(entity.getScopeType());
                    existing.setPriority(entity.getPriority());
                    existing.setActive(entity.isActive());
                    return ApiResponse.ok(repository.save(existing));
                })
                .orElse(ApiResponse.error("레시피를 찾을 수 없습니다: " + id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Context Recipe 삭제")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id) {
        if (!repository.existsById(id)) {
            return ApiResponse.error("레시피를 찾을 수 없습니다: " + id);
        }
        repository.deleteById(id);
        return ApiResponse.ok(Map.of("deleted", id));
    }
}
