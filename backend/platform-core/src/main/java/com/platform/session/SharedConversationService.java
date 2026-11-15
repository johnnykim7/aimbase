package com.platform.session;

import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CR-194: 함께 보는 대화. 한 건을 여는 화면에서 그 건을 여는 사람 모두가 대화 하나를 같이 보고 이어 묻는다.
 * 붙이는 앱 서버가 그 사람이 그 건을 볼 수 있다고 보증한 함께 보기 키를 토큰에 실어 오고, aimbase 는 같은 키인지만 본다.
 */
@Service
public class SharedConversationService {

    public static final String SHARE_KEY_REQUIRED = "share_key_required";
    public static final String REWIND_REJECTED = "rewind_rejected:shared";

    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;

    public SharedConversationService(ConversationSessionRepository sessionRepository,
                                     ConversationMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    public record Opened(String sessionId, boolean created) {}

    /**
     * PRD-632 BIZ-276: 그 키의 대화가 있으면 그 번호, 없으면 메시지 없는 대화를 만들어 그 번호. 처음 여는 두 사람이 동시에
     * 와도 부분 고유 색인으로 하나만 남고, 늦은 쪽은 먼저 만든 대화를 받는다.
     */
    public Opened open(String shareKey) {
        if (shareKey == null) throw new ChatRejectedException(HttpStatus.BAD_REQUEST, SHARE_KEY_REQUIRED);
        var existing = sessionRepository.findLiveByShareKey(shareKey);
        if (existing.isPresent()) return new Opened(existing.get().getSessionId(), false);
        String sessionId = UUID.randomUUID().toString();
        if (sessionRepository.insertSharedIfAbsent(UUID.randomUUID(), sessionId, shareKey) == 1) {
            return new Opened(sessionId, true);
        }
        return sessionRepository.findLiveByShareKey(shareKey)
                .map(s -> new Opened(s.getSessionId(), false))
                .orElseThrow(() -> new IllegalStateException("shared conversation vanished: " + shareKey));
    }

    /**
     * BIZ-280: 함께 보는 대화의 다시 생성과 질문 고치기는 마지막 질문 묶음에만, 그 질문을 한 사람만 한다. 뒤에 다른 사람의
     * 질문이 있는 묶음을 되감으면 남의 질문이 지워지기 때문이다. 묻는 사람이 남지 않은 질문(CR-194 전)은 되감지 않는다.
     */
    public void checkRewind(String sessionId, int fromSeq, String requesterRef) {
        sessionRepository.findBySessionId(sessionId)
                .filter(ConversationSessionEntity::isShared)
                .ifPresent(s -> checkRewind(s, fromSeq, requesterRef));
    }

    void checkRewind(ConversationSessionEntity session, int fromSeq, String requesterRef) {
        List<ConversationMessageEntity> rows = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getSessionId());
        UUID lastTurn = null;
        for (ConversationMessageEntity m : rows) {
            if (m.getTurnId() != null) lastTurn = m.getTurnId();
        }
        if (lastTurn == null) throw rejected();
        Integer firstSeq = null;
        String askerRef = null;
        for (ConversationMessageEntity m : rows) {
            if (!lastTurn.equals(m.getTurnId())) continue;
            if (firstSeq == null) firstSeq = m.getSeq();
            if ("user".equals(m.getRole())) {
                String ref = askerRef(m.getDisplay());
                if (ref != null) askerRef = ref;
            }
        }
        if (firstSeq == null || firstSeq != fromSeq || askerRef == null || !askerRef.equals(requesterRef)) {
            throw rejected();
        }
    }

    /** 질문 줄 화면용 값의 묻는 사람 user_ref. */
    static String askerRef(Map<String, Object> display) {
        if (display == null || !(display.get(AskerKeys.ASKER) instanceof Map<?, ?> asker)) return null;
        return asker.get(AskerKeys.USER_REF) instanceof String s && !s.isBlank() ? s : null;
    }

    private static ChatRejectedException rejected() {
        return new ChatRejectedException(HttpStatus.CONFLICT, REWIND_REJECTED);
    }

    /** 질문 줄 화면용 값(display)에 남기는 묻는 사람 칸 이름(BIZ-278). */
    public static final class AskerKeys {
        public static final String ASKER = "asker";
        public static final String USER_REF = "user_ref";
        public static final String USER_NAME = "user_name";

        private AskerKeys() {}
    }
}
