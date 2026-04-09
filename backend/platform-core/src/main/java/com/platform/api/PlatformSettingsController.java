package com.platform.api;

import com.platform.config.PlatformSettingsService;
import com.platform.config.PlatformSettingsService.SettingItem;
import com.platform.config.PlatformSettingsService.SettingUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * CR-040: 플랫폼 런타임 설정 관리 API.
 * Super Admin 전용 (/api/v1/platform/**)
 */
@RestController
@RequestMapping("/api/v1/platform/settings")
@Tag(name = "Platform Settings", description = "런타임 설정 관리 API (Super Admin)")
public class PlatformSettingsController {

    private final PlatformSettingsService settingsService;

    public PlatformSettingsController(PlatformSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    @Operation(summary = "런타임 설정 조회", description = "카테고리별 그룹핑된 설정 반환. category 파라미터로 필터 가능.")
    public Map<String, Object> getSettings(
            @RequestParam(required = false) String category) {
        Map<String, List<SettingItem>> grouped = settingsService.getAllGrouped(category);
        return Map.of("success", true, "data", grouped);
    }

    @PutMapping
    @Operation(summary = "런타임 설정 수정", description = "단건/다건 설정 수정. 변경 시 캐시 무효화 + 감사 로그 기록.")
    public Map<String, Object> updateSettings(@RequestBody UpdateRequest request) {
        if (request.settings() == null || request.settings().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "settings must not be empty");
        }

        String userId = request.userId() != null ? request.userId() : "admin";

        try {
            int updated = settingsService.updateSettings(request.settings(), userId);
            Map<String, List<SettingItem>> refreshed = settingsService.getAllGrouped(null);
            return Map.of("success", true, "updated", updated, "data", refreshed);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/evict-cache")
    @Operation(summary = "설정 캐시 무효화", description = "전체 캐시를 강제 무효화한다.")
    public Map<String, Object> evictCache() {
        settingsService.evictAll();
        return Map.of("success", true, "message", "Cache evicted");
    }

    public record UpdateRequest(List<SettingUpdateRequest> settings, String userId) {}
}
