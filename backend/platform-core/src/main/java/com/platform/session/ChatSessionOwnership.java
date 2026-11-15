package com.platform.session;

import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ConversationSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 대화에 들어갈 수 있는지 한 곳에서 본다(CR-182, CR-194 BIZ-275). 들어갈 수 없으면 없는 것처럼 404 를 돌려
 * 남의 대화가 있다는 것도 알리지 않는다. 답 찾기(CR-175 ChatTurnLookup)와 이어보기도 같은 규칙을 쓴다.
 *
 * <p>함께 보기 키가 있는 대화는 같은 키를 실은 위젯 토큰만 들어간다. 키가 없는 대화는 주인만 들어가고, 주인이 빈 대화
 * (CR-178 전 대화와 user_ref 없는 토큰으로 만든 대화)는 지금처럼 번호를 아는 요청에 열린다. 함께 보기 키는 붙이는 앱
 * 서버가 보증해 토큰에만 싣는 값이라 지금 요청의 토큰에서 읽는다.
 */
@Component
public class ChatSessionOwnership {

    public static final String SESSION_NOT_FOUND = "session_not_found";

    private final ConversationSessionRepository sessionRepository;

    public ChatSessionOwnership(ConversationSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * BIZ-275 규칙. 지운 대화인지는 보지 않는다(창구마다 다르다).
     *
     * @param requesterRef 요청자 주인 값(ChatOwner)
     * @param shareKey     요청 토큰의 함께 보기 키. 없으면 null
     */
    public static boolean allows(ConversationSessionEntity session, String requesterRef, String shareKey) {
        if (session.isShared()) return session.getShareKey().equals(shareKey);
        return session.getUserId() == null || session.getUserId().equals(requesterRef);
    }

    /** 로그인 대화는 CR-178 전에 이메일로 주인을 적었으므로 ChatOwner.owns 로 둘 다 본다. */
    public static boolean allows(ConversationSessionEntity session, Authentication auth) {
        if (session.isShared()) return session.getShareKey().equals(ChatOwner.shareKeyOf(auth));
        return session.getUserId() == null || ChatOwner.owns(session.getUserId(), auth);
    }

    public ConversationSessionEntity require(String requesterRef, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw notFound();
        ConversationSessionEntity session = sessionRepository.findBySessionId(sessionId).orElseThrow(this::notFound);
        if (session.getDeletedAt() != null) throw notFound();
        if (!allows(session, requesterRef, ChatOwner.currentShareKey())) throw notFound();
        return session;
    }

    /** 세션 행이 아직 없으면 통과(첫 메시지 저장 전). */
    public boolean ownedOrUnclaimed(String requesterRef, String sessionId) {
        String shareKey = ChatOwner.currentShareKey();
        return sessionRepository.findBySessionId(sessionId)
                .map(s -> s.getDeletedAt() == null && allows(s, requesterRef, shareKey))
                .orElse(true);
    }

    /**
     * 대화 요청, 응답 중지, 첨부처럼 아직 없는 대화 번호가 올 수 있는 창구. 행이 없거나 번호가 비었으면 통과한다.
     * 대화 요청은 응답을 시작하기 전에 불러 오류를 HTTP 코드로 준다.
     */
    public void requireIfExists(String sessionId) {
        if (!allowsIfExists(sessionId)) throw notFound();
    }

    public boolean allowsIfExists(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return true;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return sessionRepository.findBySessionId(sessionId).map(s -> allows(s, auth)).orElse(true);
    }

    private ChatRejectedException notFound() {
        return new ChatRejectedException(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND);
    }
}
