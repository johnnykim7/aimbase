package com.platform.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.domain.MessageFeedbackEntity;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.MessageFeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageFeedbackServiceTest {

    @Mock private MessageFeedbackRepository feedbackRepository;
    @Mock private ConversationMessageRepository messageRepository;
    @Mock private ChatTurnLookup turnLookup;
    @Mock private ChatWidgetSettingsService widgetSettings;

    private MessageFeedbackService service;
    private final UUID turnId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MessageFeedbackService(feedbackRepository, messageRepository, turnLookup, widgetSettings,
                new ObjectMapper());
        lenient().when(widgetSettings.feedbackReasons()).thenReturn(List.of(
                Map.of("code", "NO_LOOKUP", "label", "조회를 안 함"),
                Map.of("code", "TOO_LONG", "label", "너무 김")));
        lenient().when(widgetSettings.feedbackCommentMaxChars()).thenReturn(500);
    }

    @Test
    void down_with_reason_is_upserted_once_per_person() {
        // CR-175 TC-CHT-006: 같은 사람이 다시 누르면 같은 행을 바꾼다(저장은 upsert 하나)
        when(turnLookup.find("user-1", "s-1", turnId.toString())).thenReturn(turn("s-1", false));
        MessageFeedbackEntity saved = new MessageFeedbackEntity();
        saved.setTurnId(turnId);
        saved.setRating(MessageFeedbackEntity.Rating.DOWN);
        saved.setReasonCodes(List.of("NO_LOOKUP"));
        when(feedbackRepository.findByTurnIdAndUserRef(turnId, "user-1")).thenReturn(Optional.of(saved));

        MessageFeedbackEntity result = service.save("user-1", "s-1", turnId.toString(), "DOWN",
                List.of("NO_LOOKUP", "NO_LOOKUP"), " 출고 이력을 안 봤어요 ");

        verify(feedbackRepository).upsert("s-1", turnId.toString(), "user-1", "DOWN", "[\"NO_LOOKUP\"]",
                "출고 이력을 안 봤어요", "claude-sonnet-5-5");
        assertThat(result.getRating()).isEqualTo(MessageFeedbackEntity.Rating.DOWN);
    }

    @Test
    void invalid_input_is_rejected_with_field_code() {
        // CR-175 TC-CHT-007
        assertRejected(() -> service.save("u", "s-1", turnId.toString(), "MEH", null, null),
                HttpStatus.BAD_REQUEST, "feedback_invalid:rating");
        assertRejected(() -> service.save("u", "s-1", turnId.toString(), "UP", List.of("NO_LOOKUP"), null),
                HttpStatus.BAD_REQUEST, "feedback_invalid:reason_codes");
        assertRejected(() -> service.save("u", "s-1", turnId.toString(), "DOWN", List.of("UNKNOWN"), null),
                HttpStatus.BAD_REQUEST, "feedback_invalid:reason_codes");
        assertRejected(() -> service.save("u", "s-1", turnId.toString(), "DOWN", null, "가".repeat(501)),
                HttpStatus.BAD_REQUEST, "feedback_invalid:comment");
        verify(feedbackRepository, never()).upsert(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
    }

    @Test
    void someone_elses_turn_is_not_found() {
        // CR-175 TC-CHT-007: 남의 세션 답은 404 (ChatTurnLookup 이 던진다)
        when(turnLookup.find("intruder", "s-1", turnId.toString()))
                .thenThrow(new ChatRejectedException(HttpStatus.NOT_FOUND, "turn_not_found"));

        assertRejected(() -> service.save("intruder", "s-1", turnId.toString(), "UP", null, null),
                HttpStatus.NOT_FOUND, "turn_not_found");
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_marks_rewound_answers_and_resolves_labels() {
        // CR-175 TC-CHT-008: 되감기로 지운 답에 남긴 평가도 목록에 남고 answer_deleted 로 보인다
        MessageFeedbackEntity f = new MessageFeedbackEntity();
        f.setSessionId("s-1");
        f.setTurnId(turnId);
        f.setUserRef("user-1");
        f.setRating(MessageFeedbackEntity.Rating.DOWN);
        f.setReasonCodes(List.of("NO_LOOKUP", "RETIRED_CODE"));
        when(feedbackRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(f)));
        when(messageRepository.findByTurnIdIn(any())).thenReturn(turn("s-1", true).messages());

        Page<Map<String, Object>> page = service.list("DOWN", "2026-11-01T00:00:00+09:00", null, null, 0, 20);

        Map<String, Object> row = page.getContent().get(0);
        assertThat(row.get("answer_deleted")).isEqualTo(true);
        assertThat(row.get("question_excerpt")).isEqualTo("박스가 몇 개 나갔나요?");
        assertThat((List<Map<String, String>>) row.get("reasons")).containsExactly(
                Map.of("code", "NO_LOOKUP", "label", "조회를 안 함"),
                Map.of("code", "RETIRED_CODE", "label", "RETIRED_CODE"));
    }

    @Test
    void list_rejects_time_without_zone() {
        assertRejected(() -> service.list(null, "2026-11-01", null, null, 0, 20),
                HttpStatus.BAD_REQUEST, "feedback_invalid:from");
    }

    private ChatTurnLookup.Turn turn(String sessionId, boolean answerDeleted) {
        ConversationSessionEntity session = new ConversationSessionEntity();
        session.setSessionId(sessionId);
        ConversationMessageEntity context = message(sessionId, 4, "system", "문의 맥락", null);
        ConversationMessageEntity question = message(sessionId, 5, "user", "박스가 몇 개 나갔나요?", null);
        ConversationMessageEntity answer = message(sessionId, 6, "assistant", "13박스 중 12박스입니다.", "claude-sonnet-5-5");
        if (answerDeleted) answer.setDeletedAt(OffsetDateTime.now());
        return new ChatTurnLookup.Turn(session, turnId, List.of(context, question, answer));
    }

    private ConversationMessageEntity message(String sessionId, int seq, String role, String content, String model) {
        ConversationMessageEntity m = new ConversationMessageEntity();
        m.setSessionId(sessionId);
        m.setSeq(seq);
        m.setRole(role);
        m.setContent(content);
        m.setModel(model);
        m.setTurnId(turnId);
        return m;
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                       HttpStatus status, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(ChatRejectedException.class)
                .hasMessage(code)
                .extracting("status").isEqualTo(status);
    }
}
