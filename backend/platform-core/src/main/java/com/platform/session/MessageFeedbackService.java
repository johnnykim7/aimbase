package com.platform.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.MessageFeedbackEntity;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.MessageFeedbackRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CR-175: 답 평가 남기기, 바꾸기, 취소와 관리 화면 목록(PRD-370, BIZ-149).
 */
@Service
public class MessageFeedbackService {

    /** 관리 목록 한 줄에 보이는 질문, 답 앞부분 글자 수. 전문은 세션 대화에서 본다. */
    static final int EXCERPT_CHARS = 120;
    /** 관리 목록 한 쪽 최대 행 수. 큰 값으로 한 번에 긁어 가는 것을 막는다. */
    static final int MAX_PAGE_SIZE = 100;

    private final MessageFeedbackRepository feedbackRepository;
    private final ConversationMessageRepository messageRepository;
    private final ChatTurnLookup turnLookup;
    private final ChatWidgetSettingsService widgetSettings;
    private final ObjectMapper objectMapper;

    public MessageFeedbackService(MessageFeedbackRepository feedbackRepository,
                                  ConversationMessageRepository messageRepository,
                                  ChatTurnLookup turnLookup,
                                  ChatWidgetSettingsService widgetSettings,
                                  ObjectMapper objectMapper) {
        this.feedbackRepository = feedbackRepository;
        this.messageRepository = messageRepository;
        this.turnLookup = turnLookup;
        this.widgetSettings = widgetSettings;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MessageFeedbackEntity save(String requesterRef, String sessionId, String turnIdText,
                                      String ratingText, List<String> reasonCodes, String comment) {
        MessageFeedbackEntity.Rating rating = parseRating(ratingText);
        List<String> codes = validateReasons(rating, reasonCodes);
        String trimmedComment = comment == null || comment.isBlank() ? null : comment.trim();
        if (trimmedComment != null && trimmedComment.length() > widgetSettings.feedbackCommentMaxChars()) {
            throw invalid("comment");
        }
        ChatTurnLookup.Turn turn = turnLookup.find(requesterRef, sessionId, turnIdText);
        feedbackRepository.upsert(sessionId, turn.turnId().toString(), requesterRef, rating.name(),
                toJson(codes), trimmedComment, turn.model());
        return feedbackRepository.findByTurnIdAndUserRef(turn.turnId(), requesterRef)
                .orElseThrow(() -> new IllegalStateException("Feedback row missing right after upsert"));
    }

    /** 내 평가 취소. 남긴 적이 없어도 성공이다(같은 단추를 두 번 누른 경우). */
    @Transactional
    public int delete(String requesterRef, String turnIdText) {
        UUID turnId = ChatTurnLookup.parseTurnId(turnIdText);
        if (turnId == null) {
            throw new ChatRejectedException(HttpStatus.NOT_FOUND, "turn_not_found");
        }
        return feedbackRepository.deleteByTurnIdAndUserRef(turnId, requesterRef);
    }

    /** 관리 화면 평가 목록. from 은 포함, to 는 제외하는 ISO-8601 시각(시간대 포함). */
    @Transactional(readOnly = true)
    public Page<Map<String, Object>> list(String rating, String from, String to, String model, int page, int size) {
        MessageFeedbackEntity.Rating ratingFilter = rating == null || rating.isBlank() ? null : parseRating(rating);
        OffsetDateTime fromTs = parseTime(from, "from");
        OffsetDateTime toTs = parseTime(to, "to");
        Specification<MessageFeedbackEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (ratingFilter != null) predicates.add(cb.equal(root.get("rating"), ratingFilter));
            if (fromTs != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromTs));
            if (toTs != null) predicates.add(cb.lessThan(root.get("createdAt"), toTs));
            if (model != null && !model.isBlank()) predicates.add(cb.equal(root.get("model"), model.trim()));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<MessageFeedbackEntity> rows = feedbackRepository.findAll(spec,
                PageRequest.of(Math.max(page, 0), pageSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        Set<UUID> turnIds = rows.getContent().stream().map(MessageFeedbackEntity::getTurnId).collect(Collectors.toSet());
        Map<UUID, List<ConversationMessageEntity>> messagesByTurn = turnIds.isEmpty() ? Map.of()
                : messageRepository.findByTurnIdIn(turnIds).stream()
                        .collect(Collectors.groupingBy(ConversationMessageEntity::getTurnId));
        Map<String, String> labels = new LinkedHashMap<>();
        widgetSettings.feedbackReasons().forEach(r -> labels.put(r.get("code"), r.get("label")));

        return rows.map(f -> toRow(f, messagesByTurn.getOrDefault(f.getTurnId(), List.of()), labels));
    }

    public Map<String, Object> toResponse(MessageFeedbackEntity f) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", f.getId());
        body.put("turn_id", f.getTurnId().toString());
        body.put("rating", f.getRating().name());
        body.put("reason_codes", f.getReasonCodes());
        body.put("comment", f.getComment());
        body.put("updated_at", f.getUpdatedAt());
        return body;
    }

    private Map<String, Object> toRow(MessageFeedbackEntity f, List<ConversationMessageEntity> messages,
                                      Map<String, String> labels) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", f.getId());
        row.put("created_at", f.getCreatedAt());
        row.put("updated_at", f.getUpdatedAt());
        row.put("rating", f.getRating().name());
        row.put("reasons", f.getReasonCodes().stream()
                .map(code -> Map.of("code", code, "label", labels.getOrDefault(code, code)))
                .toList());
        row.put("comment", f.getComment());
        row.put("model", f.getModel());
        row.put("session_id", f.getSessionId());
        row.put("question_excerpt", excerpt(ChatTurnLookup.Turn.lastText(messages, "user")));
        row.put("answer_excerpt", excerpt(ChatTurnLookup.Turn.lastText(messages, "assistant")));
        row.put("answer_deleted", messages.stream()
                .filter(m -> "assistant".equals(m.getRole()))
                .allMatch(m -> m.getDeletedAt() != null));
        return row;
    }

    private MessageFeedbackEntity.Rating parseRating(String text) {
        try {
            return MessageFeedbackEntity.Rating.valueOf(text == null ? "" : text.trim());
        } catch (IllegalArgumentException e) {
            throw invalid("rating");
        }
    }

    private List<String> validateReasons(MessageFeedbackEntity.Rating rating, List<String> reasonCodes) {
        if (reasonCodes == null || reasonCodes.isEmpty()) return List.of();
        if (rating != MessageFeedbackEntity.Rating.DOWN) throw invalid("reason_codes");
        Set<String> allowed = widgetSettings.feedbackReasons().stream()
                .map(r -> r.get("code")).collect(Collectors.toSet());
        Set<String> unique = new LinkedHashSet<>(reasonCodes);
        if (!allowed.containsAll(unique)) throw invalid("reason_codes");
        return List.copyOf(unique);
    }

    private OffsetDateTime parseTime(String text, String field) {
        if (text == null || text.isBlank()) return null;
        try {
            return OffsetDateTime.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw invalid(field);
        }
    }

    private String toJson(List<String> codes) {
        try {
            return objectMapper.writeValueAsString(codes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize reason codes", e);
        }
    }

    private static String excerpt(String text) {
        if (text == null) return "";
        String flat = text.strip();
        return flat.length() <= EXCERPT_CHARS ? flat : flat.substring(0, EXCERPT_CHARS);
    }

    private static ChatRejectedException invalid(String field) {
        return new ChatRejectedException(HttpStatus.BAD_REQUEST, "feedback_invalid:" + field);
    }
}
