package com.platform.api;

import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * CR-049 PRD-303: 장기 세션 무손실 재개 (Session Resume).
 * 가장 최근 COMPACT_BOUNDARY 메시지 이후 체인과 보존 context 를 반환한다.
 * 24h TTL 이내 active(Soft Delete 제외) 세션만 대상.
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionResumeController {

    private static final long SESSION_TTL_HOURS = 24;

    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;

    public SessionResumeController(ConversationSessionRepository sessionRepository,
                                    ConversationMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    @PostMapping("/{sessionId}/resume")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> resume(@PathVariable String sessionId,
                                     Authentication auth) {
        Optional<ConversationSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("세션을 찾을 수 없거나 이미 삭제되었습니다."));
        }
        ConversationSessionEntity session = sessionOpt.get();

        // BIZ-002: 24h TTL 이내 active 세션만 (만료는 별도 archived 조회 — 본 CR 범위 밖)
        if (session.getUpdatedAt() != null
                && session.getUpdatedAt().isBefore(OffsetDateTime.now().minusHours(SESSION_TTL_HOURS))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("세션 TTL(24h) 초과. archived 조회 필요."));
        }

        // 본인 소유 검증 — session.userId 가 세팅된 경우만 엄격 검증 (레거시 null 허용)
        String principal = auth != null ? auth.getName() : null;
        if (session.getUserId() != null && principal != null && !session.getUserId().equals(principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("세션 소유자가 아닙니다."));
        }

        // 가장 최근 COMPACT_BOUNDARY 조회
        List<ConversationMessageEntity> boundaryHits =
                messageRepository.findLatestBoundary(sessionId, PageRequest.of(0, 1));
        ConversationMessageEntity boundary = boundaryHits.isEmpty() ? null : boundaryHits.get(0);

        // boundary 이후(포함) 활성 메시지 반환. boundary 가 없으면 전체.
        List<ConversationMessageEntity> messages;
        if (boundary != null) {
            messages = messageRepository.findBySessionIdSince(sessionId, boundary.getCreatedAt());
        } else {
            messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", sessionId);
        body.put("resumed_at", OffsetDateTime.now().toString());
        body.put("boundary", boundary == null ? null : toBoundaryInfo(boundary));
        body.put("messages", messages.stream().map(SessionResumeController::toMessageDto).toList());
        body.put("preserved_context", Map.of(
                "title", session.getTitle(),
                "model", session.getModel(),
                "summary_text", session.getSummaryText()));
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    private static Map<String, Object> toBoundaryInfo(ConversationMessageEntity b) {
        Map<String, Object> meta = b.getBoundaryMeta() != null
                ? new LinkedHashMap<>(b.getBoundaryMeta())
                : new LinkedHashMap<>();
        meta.putIfAbsent("boundary_at", b.getCreatedAt().toString());
        return meta;
    }

    private static Map<String, Object> toMessageDto(ConversationMessageEntity m) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", m.getId());
        dto.put("role", m.getRole());
        dto.put("message_type", m.getMessageType());
        dto.put("content", m.getContent());
        dto.put("tokens", m.getTokens());
        dto.put("created_at", m.getCreatedAt());
        if (ConversationMessageEntity.TYPE_COMPACT_BOUNDARY.equals(m.getMessageType())) {
            dto.put("boundary_meta", m.getBoundaryMeta());
        }
        return dto;
    }
}
