package com.platform.api;

import com.platform.session.ChatHistoryService;
import com.platform.session.ChatOwner;
import com.platform.session.ChatRejectedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    public ChatHistoryController(ChatHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_chat:stream') or isAuthenticated()")
    @Operation(summary = "내 지난 대화 목록")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(name = "chat_project_id", required = false) String chatProjectId,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.page(historyService.list(requireOwner(), chatProjectId, query, page, size));
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
