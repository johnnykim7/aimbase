package com.platform.session;

import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ConversationSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 세션 주인 대조(CR-182). 답 찾기(CR-175 ChatTurnLookup)와 같은 규칙이다. 세션 주인이 적혀 있는데
 * 요청한 사람이 아니면 없는 것처럼 404 를 돌려 남의 세션이 있다는 것도 알리지 않는다.
 */
@Component
public class ChatSessionOwnership {

    public static final String SESSION_NOT_FOUND = "session_not_found";

    private final ConversationSessionRepository sessionRepository;

    public ChatSessionOwnership(ConversationSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public ConversationSessionEntity require(String requesterRef, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw notFound();
        ConversationSessionEntity session = sessionRepository.findBySessionId(sessionId).orElseThrow(this::notFound);
        if (session.getDeletedAt() != null) throw notFound();
        if (session.getUserId() != null && !session.getUserId().equals(requesterRef)) throw notFound();
        return session;
    }

    /** 세션 주인 대조만 한다. 세션 행이 아직 없으면 통과(첫 메시지 저장 전). */
    public boolean ownedOrUnclaimed(String requesterRef, String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .map(s -> s.getDeletedAt() == null
                        && (s.getUserId() == null || s.getUserId().equals(requesterRef)))
                .orElse(true);
    }

    private ChatRejectedException notFound() {
        return new ChatRejectedException(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND);
    }
}
