package com.platform.api;

import com.platform.skill.SkillBundleService;
import com.platform.skill.SkillScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * CR-147 PRD-341: 플랫폼 공용 스킬 묶음 (Master DB, BIZ-024). 모든 테넌트가 같이 쓴다.
 */
@RestController
@RequestMapping("/api/v1/platform/skill-bundles")
@Tag(name = "Platform Skill Bundles", description = "플랫폼 공용 스킬 묶음")
public class PlatformSkillBundleController {

    private final SkillBundleService service;

    public PlatformSkillBundleController(SkillBundleService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "플랫폼 공용 스킬 묶음 목록")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(service.listForPlatform());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "플랫폼 공용 스킬 묶음 올리기 (새 판)")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(SkillBundleController.toMap(
                service.upload(SkillScope.PLATFORM, file, SkillBundleController.currentUserId())));
    }

    @GetMapping("/{name}")
    @Operation(summary = "플랫폼 공용 스킬 묶음 상세와 판 목록")
    public ApiResponse<Map<String, Object>> get(@PathVariable String name) {
        return ApiResponse.ok(service.getBundle(name, SkillScope.PLATFORM));
    }

    @GetMapping("/{name}/versions/{version}")
    @Operation(summary = "플랫폼 공용 스킬 묶음 판 상세")
    public ApiResponse<Map<String, Object>> getVersion(@PathVariable String name, @PathVariable int version) {
        return ApiResponse.ok(service.getVersion(name, version, SkillScope.PLATFORM));
    }

    @GetMapping("/{name}/versions/{version}/file")
    @Operation(summary = "플랫폼 공용 스킬 묶음 판의 파일 하나")
    public ApiResponse<Map<String, Object>> getFile(@PathVariable String name, @PathVariable int version,
                                                    @RequestParam String path) {
        return ApiResponse.ok(service.getFile(name, version, path, SkillScope.PLATFORM));
    }

    @PatchMapping("/{name}")
    @Operation(summary = "플랫폼 공용 스킬 묶음 활성, 비활성")
    public ApiResponse<Map<String, Object>> patch(@PathVariable String name, @RequestBody Map<String, Object> body) {
        if (!(body.get("is_active") instanceof Boolean active)) {
            throw new IllegalArgumentException("is_active 는 true 나 false 여야 합니다");
        }
        service.setActive(SkillScope.PLATFORM, name, active, SkillBundleController.currentUserId());
        return ApiResponse.ok(service.getBundle(name, SkillScope.PLATFORM));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "플랫폼 공용 스킬 묶음 비활성화 (판은 남는다, BIZ-119)")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String name) {
        service.setActive(SkillScope.PLATFORM, name, false, SkillBundleController.currentUserId());
        return ApiResponse.ok(service.getBundle(name, SkillScope.PLATFORM));
    }
}
