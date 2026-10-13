package com.platform.api;

import com.platform.auth.UserPrincipal;
import com.platform.skill.SkillBundleService;
import com.platform.skill.SkillScope;
import com.platform.skill.UploadOutcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-147 PRD-341: 테넌트 스킬 묶음 (Tenant DB). 목록과 조회는 플랫폼 공용도 같이 보여주고,
 * 올리기와 활성 바꾸기는 테넌트 전용만 한다.
 */
@RestController
@RequestMapping("/api/v1/skill-bundles")
@Tag(name = "Skill Bundles", description = "스킬 묶음 (Claude Code 스킬 폴더 zip)")
public class SkillBundleController {

    private final SkillBundleService service;

    public SkillBundleController(SkillBundleService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "스킬 묶음 목록 (테넌트 전용과 플랫폼 공용)")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(service.listForTenant());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "테넌트 전용 스킬 묶음 올리기 (새 판)")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(toMap(service.upload(SkillScope.TENANT, file, currentUserId())));
    }

    @GetMapping("/{name}")
    @Operation(summary = "스킬 묶음 상세와 판 목록")
    public ApiResponse<Map<String, Object>> get(@PathVariable String name,
                                                @RequestParam(required = false) String scope) {
        return ApiResponse.ok(service.getBundleForTenant(name, SkillScope.fromValue(scope)));
    }

    @GetMapping("/{name}/versions/{version}")
    @Operation(summary = "스킬 묶음 판 상세")
    public ApiResponse<Map<String, Object>> getVersion(@PathVariable String name, @PathVariable int version,
                                                       @RequestParam(required = false) String scope) {
        return ApiResponse.ok(service.getVersion(name, version, SkillScope.fromValue(scope)));
    }

    @GetMapping("/{name}/versions/{version}/file")
    @Operation(summary = "스킬 묶음 판의 파일 하나")
    public ApiResponse<Map<String, Object>> getFile(@PathVariable String name, @PathVariable int version,
                                                    @RequestParam String path,
                                                    @RequestParam(required = false) String scope) {
        return ApiResponse.ok(service.getFile(name, version, path, SkillScope.fromValue(scope)));
    }

    @PatchMapping("/{name}")
    @Operation(summary = "테넌트 전용 스킬 묶음 활성, 비활성")
    public ApiResponse<Map<String, Object>> patch(@PathVariable String name, @RequestBody Map<String, Object> body) {
        if (!(body.get("is_active") instanceof Boolean active)) {
            throw new IllegalArgumentException("is_active 는 true 나 false 여야 합니다");
        }
        service.setActive(SkillScope.TENANT, name, active, currentUserId());
        return ApiResponse.ok(service.getBundle(name, SkillScope.TENANT));
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "테넌트 전용 스킬 묶음 비활성화 (판은 남는다, BIZ-119)")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String name) {
        service.setActive(SkillScope.TENANT, name, false, currentUserId());
        return ApiResponse.ok(service.getBundle(name, SkillScope.TENANT));
    }

    static Map<String, Object> toMap(UploadOutcome outcome) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", outcome.name());
        m.put("scope", outcome.scope().value());
        m.put("version", outcome.version());
        m.put("created", outcome.created());
        m.put("reactivated", outcome.reactivated());
        m.put("warnings", outcome.warnings());
        return m;
    }

    static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        if (auth.getPrincipal() instanceof UserPrincipal up) return up.getUsername();
        return auth.getName();
    }
}
