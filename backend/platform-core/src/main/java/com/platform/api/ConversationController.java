package com.platform.api;

import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.session.SessionStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "Conversations", description = "대화 히스토리 관리")
public class ConversationController {

    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;
    private final SessionStore sessionStore;

    public ConversationController(ConversationSessionRepository sessionRepository,
                                   ConversationMessageRepository messageRepository,
                                   SessionStore sessionStore) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.sessionStore = sessionStore;
    }

    @GetMapping
    @Operation(summary = "대화 세션 목록 조회")
    public ApiResponse<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query
    ) {
        var pageable = PageRequest.of(page, size);
        if (query != null && !query.isBlank()) {
            return ApiResponse.page(sessionRepository.searchByTitle(query, pageable));
        }
        return ApiResponse.page(sessionRepository.findAllByOrderByUpdatedAtDesc(pageable));
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "대화 세션 상세 조회 (메시지 포함)")
    public ApiResponse<Map<String, Object>> get(@PathVariable String sessionId) {
        ConversationSessionEntity session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Conversation session not found: " + sessionId));
        List<ConversationMessageEntity> messages =
                messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        return ApiResponse.ok(Map.of(
                "session", session,
                "messages", messages
        ));
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    @Operation(summary = "대화 세션 삭제")
    public void delete(@PathVariable String sessionId) {
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteBySessionId(sessionId);
        sessionStore.clearSession(sessionId);
    }

    /** CR-029: 세션 메타 조회 */
    @GetMapping("/{sessionId}/meta")
    @Operation(summary = "세션 메타 조회 (scope, runtime, recipe 등)")
    public ApiResponse<Map<String, Object>> getMeta(@PathVariable String sessionId) {
        ConversationSessionEntity session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Session not found: " + sessionId));
        return ApiResponse.ok(Map.of(
                "sessionId", session.getSessionId(),
                "scopeType", session.getScopeType() != null ? session.getScopeType() : "chat",
                "runtimeKind", session.getRuntimeKind() != null ? session.getRuntimeKind() : "",
                "workspaceRef", session.getWorkspaceRef() != null ? session.getWorkspaceRef() : "",
                "persistentSession", session.isPersistentSession(),
                "summaryVersion", session.getSummaryVersion(),
                "contextRecipeId", session.getContextRecipeId() != null ? session.getContextRecipeId() : "",
                "appId", session.getAppId() != null ? session.getAppId() : "",
                "projectId", session.getProjectId() != null ? session.getProjectId() : "",
                "parentSessionId", session.getParentSessionId() != null ? session.getParentSessionId() : ""
        ));
    }

    /** CR-029: 세션 메타 수정 */
    @PutMapping("/{sessionId}/meta")
    @Transactional
    @Operation(summary = "세션 메타 수정")
    public ApiResponse<Map<String, Object>> updateMeta(@PathVariable String sessionId,
                                                        @RequestBody Map<String, Object> body) {
        ConversationSessionEntity session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Session not found: " + sessionId));

        if (body.containsKey("scopeType")) session.setScopeType((String) body.get("scopeType"));
        if (body.containsKey("runtimeKind")) session.setRuntimeKind((String) body.get("runtimeKind"));
        if (body.containsKey("workspaceRef")) session.setWorkspaceRef((String) body.get("workspaceRef"));
        if (body.containsKey("persistentSession")) session.setPersistentSession((Boolean) body.get("persistentSession"));
        if (body.containsKey("contextRecipeId")) session.setContextRecipeId((String) body.get("contextRecipeId"));
        if (body.containsKey("appId")) session.setAppId((String) body.get("appId"));
        if (body.containsKey("projectId")) session.setProjectId((String) body.get("projectId"));
        if (body.containsKey("parentSessionId")) session.setParentSessionId((String) body.get("parentSessionId"));

        sessionRepository.save(session);
        return ApiResponse.ok(Map.of("updated", sessionId));
    }
}
