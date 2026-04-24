package com.platform.api;

import com.platform.domain.PromptTemplateEntity;
import com.platform.domain.PromptTemplateEntityId;
import com.platform.repository.PromptTemplateRepository;
import com.platform.service.PromptTemplateService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-049 PRD-305: 프로젝트 단위 시스템 지침 편의 엔드포인트.
 * scope=PROJECT 를 자동 주입하여 FE 에서 project_id 필수 누락 실수를 예방한다.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/instructions")
public class ProjectInstructionsController {

    private final PromptTemplateRepository repository;
    private final PromptTemplateService service;

    public ProjectInstructionsController(PromptTemplateRepository repository,
                                          PromptTemplateService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@PathVariable String projectId) {
        List<PromptTemplateEntity> entities = repository.findActiveProjectInstructions(projectId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project_id", projectId);
        body.put("templates", entities.stream().map(PromptTemplateEntity::toMap).toList());
        return ApiResponse.ok(body);
    }

    @PutMapping
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> upsert(@PathVariable String projectId,
                                                    @RequestBody Map<String, Object> body) {
        String key = (String) body.get("key");
        if (key == null || key.isBlank()) {
            return ApiResponse.error("key 가 필수입니다.");
        }
        // 같은 key 로 기존 PROJECT 레코드가 있으면 최신 version +1, 없으면 1부터
        int nextVersion = repository.findAllVersionsByKey(key).stream()
                .filter(e -> "PROJECT".equals(e.getScope()))
                .filter(e -> projectId.equals(e.getProjectId()))
                .mapToInt(e -> e.getPk().getVersion())
                .max()
                .orElse(0) + 1;

        PromptTemplateEntity entity = new PromptTemplateEntity();
        entity.setPk(new PromptTemplateEntityId(key, nextVersion));
        entity.setScope("PROJECT");
        entity.setProjectId(projectId);
        entity.setCategory(body.containsKey("category") ? (String) body.get("category") : "core");
        entity.setName((String) body.getOrDefault("name", key));
        entity.setDescription((String) body.get("description"));
        entity.setTemplate((String) body.get("template"));
        entity.setVariables((List<Map<String, Object>>) body.get("variables"));
        entity.setLanguage(body.getOrDefault("language", "ko").toString());
        entity.setActive(body.containsKey("is_active") ? (Boolean) body.get("is_active") : true);
        entity.setSystem(false);

        entity = repository.save(entity);
        service.invalidateCache(key);
        return ApiResponse.ok(entity.toMap());
    }
}
