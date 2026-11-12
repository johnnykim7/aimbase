package com.platform.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.auth.UserPrincipal;
import com.platform.share.ChatShareService;
import com.platform.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** CR-182 공유 링크 만들기, 목록, 끊기(3.5.1, 3.5.2). */
@RestController
@RequestMapping("/api/v1/chat/shares")
@Tag(name = "Chat Shares", description = "CR-182 대화, 파일 공유 링크")
public class ChatShareController {

    private final ChatShareService shareService;

    public ChatShareController(ChatShareService shareService) {
        this.shareService = shareService;
    }

    public record CreateShareRequest(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("kind") String kind,
            @JsonProperty("file_name") String fileName,
            @JsonProperty("ttl_days") Integer ttlDays) {}

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "공유 링크 만들기")
    public ApiResponse<Map<String, Object>> create(@AuthenticationPrincipal UserPrincipal principal,
                                                   @RequestBody CreateShareRequest body) {
        return ApiResponse.ok(shareService.create(ChatExportController.requester(principal),
                TenantContext.getTenantId(), body.sessionId(), body.kind(), body.fileName(), body.ttlDays()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "그 세션에 내가 만든 공유 링크 (최근 20개)")
    public ApiResponse<Map<String, Object>> list(@AuthenticationPrincipal UserPrincipal principal,
                                                 @RequestParam("session_id") String sessionId) {
        return ApiResponse.ok(Map.of("shares", shareService.list(ChatExportController.requester(principal),
                TenantContext.getTenantId(), sessionId)));
    }

    @DeleteMapping("/{share_id}")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "공유 링크 끊기")
    public ApiResponse<Map<String, Object>> revoke(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable("share_id") String shareId) {
        return ApiResponse.ok(shareService.revoke(ChatExportController.requester(principal),
                TenantContext.getTenantId(), shareId));
    }
}
