package com.platform.api;

import com.platform.session.ChatHistoryService;
import com.platform.session.ChatOwner;
import com.platform.session.ChatRejectedException;
import com.platform.session.SharedConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * CR-178: 위젯 지난 대화 목록, 이어보기, 이름 바꾸기, 옮기기, 삭제(PRD-420 ~ PRD-422, PRD-424).
 * chat:stream 위젯 토큰 또는 로그인으로 부른다. 주인이 없는 위젯 토큰(user_ref 없음)은 403 widget_user_required.
 */
@RestController
@RequestMapping("/api/v1/chat/sessions")
@Tag(name = "Chat History", description = "CR-178 위젯 지난 대화")
public class ChatHistoryController {

    private final ChatHistoryService historyService;
    private final SharedConversationService sharedConversations;

    public ChatHistoryController(ChatHistoryService historyService, SharedConversationService sharedConversations) {
        this.historyService = historyService;
        this.sharedConversations = sharedConversations;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "내 지난 대화 목록")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(name = "chat_project_id", required = false) String chatProjectId,
            @RequestParam(required = false) String query,
            @RequestParam(name = "screen_key", required = false) String screenKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.page(historyService.list(requireOwner(), chatProjectId, query, screenKey, page, size));
    }

    @PostMapping("/shared")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "함께 보는 대화 열기", description = "토큰의 함께 보기 키로 그 건의 대화를 찾고, 없으면 만든다(CR-194).")
    public ApiResponse<Map<String, Object>> openShared() {
        requireOwner();
        SharedConversationService.Opened opened = sharedConversations.open(ChatOwner.currentShareKey());
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("session_id", opened.sessionId());
        body.put("created", opened.created());
        return ApiResponse.ok(body);
    }

    @GetMapping("/{sessionId}/messages")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "지난 대화 이어보기")
    public ApiResponse<Map<String, Object>> messages(@PathVariable String sessionId) {
        return ApiResponse.ok(historyService.messages(requireOwner(), sessionId));
    }

    @PatchMapping("/{sessionId}")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "대화 이름 바꾸기, 프로젝트로 옮기기")
    public ApiResponse<Map<String, Object>> update(@PathVariable String sessionId,
                                                   @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(historyService.update(requireOwner(), sessionId, body));
    }

    @DeleteMapping("/{sessionId}")
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "대화 삭제")
    public ResponseEntity<Void> delete(@PathVariable String sessionId) {
        historyService.delete(requireOwner(), sessionId);
        return ResponseEntity.noContent().build();
    }

    static String requireOwner() {
        String owner = ChatOwner.current();
        if (owner == null) {
            throw new ChatRejectedException(HttpStatus.FORBIDDEN, "widget_user_required");
        }
        return owner;
    }
}
