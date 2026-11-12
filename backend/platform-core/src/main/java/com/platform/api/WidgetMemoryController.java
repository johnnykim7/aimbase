package com.platform.api;

import com.platform.auth.UserPrincipal;
import com.platform.session.WidgetPreferenceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CR-179: 위젯 사용자 본인의 내 선호, 자동 기억, 마지막에 선택한 값(PRD-440~442).
 * 주인은 위젯 토큰 user_ref 이고 없으면 403 memory_unavailable 이다(BIZ-180).
 */
@RestController
@RequestMapping("/api/v1/chat/me")
public class WidgetMemoryController {

    private final WidgetPreferenceService preferenceService;

    public WidgetMemoryController(WidgetPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @GetMapping("/preferences")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    public ApiResponse<Map<String, Object>> preferences(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(preferenceService.preferences(WidgetPreferenceService.ownerOf(principal)));
    }

    @PutMapping("/preferences")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    public ApiResponse<Map<String, Object>> updatePreferences(@AuthenticationPrincipal UserPrincipal principal,
                                                              @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(preferenceService.updatePreferences(WidgetPreferenceService.ownerOf(principal), body));
    }

    @GetMapping("/memories")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    public ApiResponse<List<Map<String, Object>>> memories(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(preferenceService.memories(WidgetPreferenceService.ownerOf(principal)));
    }

    @DeleteMapping("/memories/{id}")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    public ApiResponse<Map<String, Object>> deleteMemory(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable String id) {
        preferenceService.deleteMemory(WidgetPreferenceService.ownerOf(principal), id);
        return ApiResponse.ok(Map.of("id", id, "deleted", true));
    }

    @DeleteMapping("/memories")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    public ApiResponse<Map<String, Object>> deleteAllMemories(@AuthenticationPrincipal UserPrincipal principal) {
        long deleted = preferenceService.deleteAllMemories(WidgetPreferenceService.ownerOf(principal));
        return ApiResponse.ok(Map.of("deleted", deleted));
    }
}
