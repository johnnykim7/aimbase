package com.platform.session;

import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * CR-175: 답 평가와 후속 질문이 가리키는 답(대화 요청 한 번)을 찾는다.
 *
 * <p>답이 그 세션 것이 아니거나, 세션 주인이 적혀 있는데 요청한 사람이 아니면 404 다(BIZ-149).
 * 세션 주인은 위젯 대화를 처음 저장할 때 토큰의 user_ref 로 적힌다.
 */
@Component
public class ChatTurnLookup {

    private final ConversationSessionRepository sessionRepository;
    private final ConversationMessageRepository messageRepository;

    public ChatTurnLookup(ConversationSessionRepository sessionRepository,
                          ConversationMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    public Turn find(String requesterRef, String sessionId, String turnIdText) {
        UUID turnId = parseTurnId(turnIdText);
        if (sessionId == null || sessionId.isBlank() || turnId == null) {
            throw notFound();
        }
        ConversationSessionEntity session = sessionRepository.findBySessionId(sessionId).orElseThrow(ChatTurnLookup::notFound);
        if (session.getUserId() != null && !session.getUserId().equals(requesterRef)) {
            throw notFound();
        }
        List<ConversationMessageEntity> messages = messageRepository.findBySessionIdAndTurnId(sessionId, turnId);
        if (messages.isEmpty()) {
            throw notFound();
        }
        return new Turn(session, turnId, messages);
    }

    public static UUID parseTurnId(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ChatRejectedException notFound() {
        return new ChatRejectedException(HttpStatus.NOT_FOUND, "turn_not_found");
    }

    /** 대화 요청 한 번이 저장한 메시지 묶음. messages 는 순번 순서다. */
    public record Turn(ConversationSessionEntity session, UUID turnId, List<ConversationMessageEntity> messages) {

        public int firstSeq() {
            return messages.get(0).getSeq();
        }

        /** 이 묶음의 마지막 질문 글. 붙이는 앱이 앞에 보낸 맥락 메시지는 제외된다. */
        public String question() {
            return lastText(messages, "user");
        }

        public String answer() {
            return lastText(messages, "assistant");
        }

        public String model() {
            return messages.stream()
                    .filter(m -> "assistant".equals(m.getRole()) && m.getModel() != null)
                    .map(ConversationMessageEntity::getModel)
                    .reduce((a, b) -> b)
                    .orElse(null);
        }

        public boolean answerDeleted() {
            return messages.stream()
                    .filter(m -> "assistant".equals(m.getRole()))
                    .anyMatch(m -> m.getDeletedAt() != null);
        }

        static String lastText(List<ConversationMessageEntity> messages, String role) {
            return messages.stream()
                    .filter(m -> role.equals(m.getRole()))
                    .map(ConversationMessageEntity::getContent)
                    .reduce((a, b) -> b)
                    .orElse("");
        }
    }
}
