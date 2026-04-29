package com.platform.api;

import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.session.CancellationRegistry;
import com.platform.session.SessionStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "Conversations", description = "대화 히스토리 관리")
public class ConversationController {

    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;
    private final SessionStore sessionStore;
    private final CancellationRegistry cancellationRegistry;

    public ConversationController(ConversationSessionRepository sessionRepository,
                                   ConversationMessageRepository messageRepository,
                                   SessionStore sessionStore,
                                   CancellationRegistry cancellationRegistry) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.sessionStore = sessionStore;
        this.cancellationRegistry = cancellationRegistry;
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

    /**
     * CR-046: Soft Delete + 본인 권한 체크 + 활성 스트림 자동 abort.
     * deleted_at 컬럼만 마킹하므로 DB 데이터는 보존되며 감사·과금 로그도 그대로 남는다.
     */
    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    @Operation(summary = "대화 세션 Soft Delete (본인만 가능)")
    public void delete(@PathVariable String sessionId) {
        ConversationSessionEntity session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Conversation session not found: " + sessionId));

        // 본인 권한 체크: 세션 user_id가 비어있으면(레거시) 통과, 있으면 일치 필요.
        String currentUser = currentUserId();
        if (session.getUserId() != null && !session.getUserId().isBlank()
                && currentUser != null && !session.getUserId().equals(currentUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "본인 세션만 삭제 가능: " + sessionId);
        }

        // 활성 스트림 abort
        cancellationRegistry.cancel(sessionId);

        OffsetDateTime now = OffsetDateTime.now();
        session.setDeletedAt(now);
        sessionRepository.save(session);
        messageRepository.softDeleteBySessionId(sessionId, now);
        sessionStore.clearSession(sessionId);
    }

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        Object principal = auth.getPrincipal();
        if (principal instanceof com.platform.auth.UserPrincipal up) return up.getUsername();
        return auth.getName();
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
        // CR-045 follow-up: FE 세션 목록에서 제목 편집 지원
        if (body.containsKey("title")) session.setTitle((String) body.get("title"));

        sessionRepository.save(session);
        return ApiResponse.ok(Map.of("updated", sessionId));
    }

    /**
     * CR-045 follow-up: FE의 NewChatModal이 세션 생성 즉시 사이드바에 표시되도록
     * 빈 세션을 pre-create. 첫 메시지 전송 전에도 conversation_sessions row가 존재.
     */
    @PostMapping
    @Operation(summary = "빈 대화 세션 사전 생성 (FE 새 대화 모달에서 호출)")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String sessionId = (String) body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        // CR-077: 위젯이 같은 sessionId로 pre-create와 첫 메시지를 거의 동시에 보내면
        // 비동기 SessionStore.persistToDb와 race가 발생하여 UNIQUE 위반 가능. 3회 재시도.
        // 각 attempt 안에서 Repository.save가 자체 트랜잭션을 열고, 충돌 시 다음 attempt의
        // findBySessionId가 race-winner row를 찾아 update path로 진입한다.
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ConversationSessionEntity session = sessionRepository.findBySessionId(sessionId)
                        .orElseGet(() -> {
                            ConversationSessionEntity s = new ConversationSessionEntity();
                            s.setSessionId(sessionId);
                            return s;
                        });
                if (body.containsKey("title")) session.setTitle((String) body.get("title"));
                if (body.containsKey("workspaceRef")) session.setWorkspaceRef((String) body.get("workspaceRef"));
                if (body.containsKey("scopeType")) session.setScopeType((String) body.get("scopeType"));
                if (session.getScopeType() == null) session.setScopeType("chat");
                sessionRepository.save(session);
                return ApiResponse.ok(Map.of("sessionId", sessionId, "created", true));
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                if (attempt == maxAttempts) throw e;
            }
        }
        return ApiResponse.ok(Map.of("sessionId", sessionId, "created", true));
    }
}
