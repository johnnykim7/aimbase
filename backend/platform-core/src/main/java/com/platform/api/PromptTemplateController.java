package com.platform.api;

import com.platform.domain.PromptTemplateEntity;
import com.platform.domain.PromptTemplateEntityId;
import com.platform.repository.PromptTemplateRepository;
import com.platform.service.PromptTemplateService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CR-036 PRD-251: 프롬프트 템플릿 CRUD + render + bulk API.
 */
@RestController
@RequestMapping("/api/v1/prompt-templates")
public class PromptTemplateController {

    private final PromptTemplateRepository repository;
    private final PromptTemplateService service;

    public PromptTemplateController(PromptTemplateRepository repository,
                                     PromptTemplateService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String category) {
        List<PromptTemplateEntity> entities;
        if (category != null && !category.isBlank()) {
            entities = repository.findByCategoryAndIsActiveTrue(category);
        } else {
            entities = repository.findByIsActiveTrue();
        }
        return ApiResponse.ok(entities.stream().map(PromptTemplateEntity::toMap).toList());
    }

    @GetMapping("/{key}/{version}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String key,
                                                 @PathVariable int version) {
        return repository.findById(new PromptTemplateEntityId(key, version))
                .map(e -> ApiResponse.ok(e.toMap()))
                .orElse(ApiResponse.error("존재하지 않는 프롬프트 템플릿: " + key + " v" + version));
    }

    @GetMapping("/{key}/versions")
    public ApiResponse<List<Map<String, Object>>> versions(@PathVariable String key) {
        List<Map<String, Object>> result = repository.findAllVersionsByKey(key).stream()
                .map(PromptTemplateEntity::toMap)
                .toList();
        return ApiResponse.ok(result);
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String key = (String) body.get("key");
        int version = body.containsKey("version") ? ((Number) body.get("version")).intValue() : 1;

        PromptTemplateEntity entity = new PromptTemplateEntity();
        entity.setPk(new PromptTemplateEntityId(key, version));
        entity.setCategory((String) body.get("category"));
        entity.setName((String) body.get("name"));
        entity.setDescription((String) body.get("description"));
        entity.setTemplate((String) body.get("template"));
        entity.setVariables((List<Map<String, Object>>) body.get("variables"));
        entity.setLanguage(body.getOrDefault("language", "en").toString());
        entity.setActive(body.containsKey("is_active") ? (Boolean) body.get("is_active") : true);
        entity.setSystem(body.containsKey("is_system") ? (Boolean) body.get("is_system") : false);

        entity = repository.save(entity);
        service.invalidateCache(key);
        return ApiResponse.ok(entity.toMap());
    }

    @PutMapping("/{key}/{version}")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> update(@PathVariable String key,
                                                    @PathVariable int version,
                                                    @RequestBody Map<String, Object> body) {
        return repository.findById(new PromptTemplateEntityId(key, version))
                .map(entity -> {
                    if (body.containsKey("name")) entity.setName((String) body.get("name"));
                    if (body.containsKey("description")) entity.setDescription((String) body.get("description"));
                    if (body.containsKey("template")) entity.setTemplate((String) body.get("template"));
                    if (body.containsKey("variables")) entity.setVariables((List<Map<String, Object>>) body.get("variables"));
                    if (body.containsKey("language")) entity.setLanguage((String) body.get("language"));
                    if (body.containsKey("is_active")) entity.setActive((Boolean) body.get("is_active"));
                    // is_system은 수정 불가

                    entity = repository.save(entity);
                    service.invalidateCache(key);
                    return ApiResponse.ok(entity.toMap());
                })
                .orElse(ApiResponse.error("존재하지 않는 프롬프트 템플릿: " + key + " v" + version));
    }

    @DeleteMapping("/{key}/{version}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String key,
                                                    @PathVariable int version) {
        PromptTemplateEntityId id = new PromptTemplateEntityId(key, version);
        return repository.findById(id)
                .map(entity -> {
                    if (entity.isSystem()) {
                        return ApiResponse.<Map<String, Object>>error("시스템 프롬프트는 삭제할 수 없습니다: " + key);
                    }
                    repository.delete(entity);
                    service.invalidateCache(key);
                    return ApiResponse.ok(Map.<String, Object>of("deleted", key, "version", version));
                })
                .orElse(ApiResponse.error("존재하지 않는 프롬프트 템플릿: " + key + " v" + version));
    }

    @PostMapping("/{key}/{version}/render")
    public ApiResponse<Map<String, Object>> render(@PathVariable String key,
                                                    @PathVariable int version,
                                                    @RequestBody Map<String, Object> variables) {
        return repository.findById(new PromptTemplateEntityId(key, version))
                .map(entity -> {
                    String rendered = service.renderTemplate(entity.getTemplate(), variables);
                    return ApiResponse.ok(Map.<String, Object>of(
                            "rendered", rendered,
                            "token_estimate", rendered.length() / 4));
                })
                .orElse(ApiResponse.error("존재하지 않는 프롬프트 템플릿: " + key + " v" + version));
    }

    @GetMapping("/bulk")
    public ApiResponse<Map<String, String>> bulk(
            @RequestParam(required = false) String category) {
        return ApiResponse.ok(service.bulkLoad(category));
    }

    @DeleteMapping("/cache")
    public ApiResponse<Map<String, Object>> clearCache() {
        service.invalidateAll();
        return ApiResponse.ok(Map.of("status", "cache_cleared"));
    }
}
