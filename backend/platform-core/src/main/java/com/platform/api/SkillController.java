package com.platform.api;

import com.platform.domain.SkillEntity;
import com.platform.repository.SkillRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-035 PRD-237: 스킬 CRUD REST API.
 */
@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private final SkillRepository skillRepository;

    public SkillController(SkillRepository skillRepository) {
        this.skillRepository = skillRepository;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> skills = skillRepository.findByIsActiveTrueOrderByNameAsc().stream()
                .map(SkillEntity::toMap)
                .toList();
        return ApiResponse.ok(skills);
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String id) {
        return skillRepository.findById(id)
                .map(s -> ApiResponse.ok(s.toMap()))
                .orElse(ApiResponse.error("존재하지 않는 스킬: " + id));
    }

    @PostMapping
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        SkillEntity skill = new SkillEntity();
        skill.setId("sk-" + UUID.randomUUID().toString().substring(0, 8));
        skill.setName((String) body.get("name"));
        skill.setDescription((String) body.get("description"));
        skill.setSystemPrompt((String) body.get("system_prompt"));
        skill.setTools((List<String>) body.get("tools"));
        skill.setOutputSchema((Map<String, Object>) body.get("output_schema"));
        skill.setTags((List<String>) body.get("tags"));

        skill = skillRepository.save(skill);
        return ApiResponse.ok(skill.toMap());
    }

    @PutMapping("/{id}")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> update(@PathVariable String id,
                                                    @RequestBody Map<String, Object> body) {
        return skillRepository.findById(id)
                .map(skill -> {
                    if (body.containsKey("name")) skill.setName((String) body.get("name"));
                    if (body.containsKey("description")) skill.setDescription((String) body.get("description"));
                    if (body.containsKey("system_prompt")) skill.setSystemPrompt((String) body.get("system_prompt"));
                    if (body.containsKey("tools")) skill.setTools((List<String>) body.get("tools"));
                    if (body.containsKey("output_schema")) skill.setOutputSchema((Map<String, Object>) body.get("output_schema"));
                    if (body.containsKey("tags")) skill.setTags((List<String>) body.get("tags"));
                    skill = skillRepository.save(skill);
                    return ApiResponse.ok(skill.toMap());
                })
                .orElse(ApiResponse.error("존재하지 않는 스킬: " + id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String id) {
        if (skillRepository.existsById(id)) {
            skillRepository.deleteById(id);
            return ApiResponse.ok(Map.of("deleted", id));
        }
        return ApiResponse.error("존재하지 않는 스킬: " + id);
    }
}
