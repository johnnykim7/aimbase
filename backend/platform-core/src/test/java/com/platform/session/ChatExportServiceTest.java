package com.platform.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.PlatformSettingsService;
import com.platform.document.DocumentRenderer;
import com.platform.document.TestFonts;
import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** CR-182 TC-CHT-084, TC-CHT-085(대화 내보내기): 응답 저장과 대화 내보내기. */
class ChatExportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T01:30:00Z");
    private static final String OWNER = "user-a";

    private ConversationSessionRepository sessionRepository;
    private ConversationMessageRepository messageRepository;
    private ChatExportService service;
    private ConversationSessionEntity session;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ConversationSessionRepository.class);
        messageRepository = mock(ConversationMessageRepository.class);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        session = new ConversationSessionEntity();
        session.setSessionId("s1");
        session.setUserId(OWNER);
        session.setTitle("3분기 반품");
        when(sessionRepository.findBySessionId("s1")).thenReturn(Optional.of(session));
        service = new ChatExportService(new ChatSessionOwnership(sessionRepository),
                new ChatTurnLookup(sessionRepository, messageRepository), messageRepository,
                new DocumentRenderer(TestFonts.find()), new ChatExportLabels(settings, new ObjectMapper()),
                settings, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("TC-CHT-084 응답 저장은 응답 글만 넣고(도구 결과, 생각 없음) 이름에 서울 시각을 붙인다")
    void save_answer_keeps_only_answer_text_and_seoul_time_in_name() throws IOException {
        UUID turnId = UUID.randomUUID();
        ConversationMessageEntity answer = message(4, "assistant", ConversationMessageEntity.TYPE_TEXT,
                "답 본문입니다", "2026-11-13T01:20:00Z");
        answer.setContentJson(List.of(
                Map.of("type", "thinking", "thinking", "생각 글"),
                Map.of("type", "text", "text", "답 본문입니다")));
        answer.setModel("claude-sonnet");
        when(messageRepository.findBySessionIdAndTurnId("s1", turnId)).thenReturn(List.of(
                message(1, "user", ConversationMessageEntity.TYPE_TEXT, "질문 글", "2026-11-13T01:19:00Z"),
                message(2, "assistant", ConversationMessageEntity.TYPE_TOOL_USE, "create_file 호출", "2026-11-13T01:19:10Z"),
                message(3, "user", ConversationMessageEntity.TYPE_TOOL_RESULT, "도구 결과 글", "2026-11-13T01:19:20Z"),
                answer));

        ChatExportService.Rendered r = service.saveAnswer(OWNER, "s1", turnId.toString(), "docx", "Asia/Seoul");

        assertThat(r.fileName()).isEqualTo("3분기 반품_20261113-1020.docx");
        String text = docxText(r.bytes());
        assertThat(text).contains("답 본문입니다").contains("2026-11-13 10:20").contains("claude-sonnet");
        assertThat(text).doesNotContain("도구 결과 글").doesNotContain("create_file 호출")
                .doesNotContain("생각 글").doesNotContain("질문 글");
    }

    @Test
    @DisplayName("TC-CHT-084 남의 user_ref 로 응답 저장은 404 turn_not_found")
    void save_answer_of_other_user_is_not_found() {
        UUID turnId = UUID.randomUUID();
        when(messageRepository.findBySessionIdAndTurnId("s1", turnId)).thenReturn(List.of(
                message(1, "assistant", ConversationMessageEntity.TYPE_TEXT, "답", "2026-11-13T01:20:00Z")));

        assertRejected(() -> service.saveAnswer("user-b", "s1", turnId.toString(), "docx", "Asia/Seoul"),
                HttpStatus.NOT_FOUND, "turn_not_found");
    }

    @Test
    @DisplayName("TC-CHT-084 응답 저장 format xlsx 는 400 export_invalid:format")
    void save_answer_with_unsupported_format_is_bad_request() {
        assertRejected(() -> service.saveAnswer(OWNER, "s1", UUID.randomUUID().toString(), "xlsx", "Asia/Seoul"),
                HttpStatus.BAD_REQUEST, "export_invalid:format");
        verifyNoInteractions(messageRepository);
    }

    @Test
    @DisplayName("TC-CHT-085 대화 md 는 살아 있는 질문과 응답을 차례대로, 도구 기록 없이 쓴다")
    void export_conversation_md_keeps_question_answer_order() {
        ConversationMessageEntity firstQuestion = message(1, "user", ConversationMessageEntity.TYPE_TEXT,
                "질문 하나", "2026-11-13T01:00:00Z");
        firstQuestion.setContentJson(List.of(
                Map.of("type", "text", "text", "질문 하나"),
                Map.of("type", "document", "filename", "견적.pdf")));
        // 되감기로 지운 응답은 저장소 질의(deleted_at IS NULL)가 거른다. 여기서는 그 결과를 돌려준다.
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc("s1")).thenReturn(List.of(
                firstQuestion,
                message(2, "assistant", ConversationMessageEntity.TYPE_TOOL_USE, "create_file 호출", "2026-11-13T01:00:10Z"),
                message(3, "user", ConversationMessageEntity.TYPE_TOOL_RESULT, "도구 결과 글", "2026-11-13T01:00:20Z"),
                message(4, "assistant", ConversationMessageEntity.TYPE_TEXT, "응답 하나", "2026-11-13T01:01:00Z"),
                message(6, "user", ConversationMessageEntity.TYPE_TEXT, "질문 둘", "2026-11-13T01:05:00Z"),
                message(7, "assistant", ConversationMessageEntity.TYPE_TEXT, "다시 쓴 응답 둘", "2026-11-13T01:06:00Z")));

        ChatExportService.Rendered r = service.exportConversation(OWNER, "s1", "md", "Asia/Seoul");

        assertThat(r.fileName()).isEqualTo("3분기 반품_20261113-1030.md");
        assertThat(r.contentType()).startsWith("text/markdown");
        String md = new String(r.bytes(), StandardCharsets.UTF_8);
        assertThat(md).contains("# 3분기 반품").contains("2026-11-13 10:30 (Asia/Seoul)");
        assertThat(md).contains("## 질문 2026-11-13 10:00").contains("## 응답 2026-11-13 10:01");
        assertThat(md).contains("*첨부: 견적.pdf*");
        assertThat(md).doesNotContain("도구 결과 글").doesNotContain("create_file 호출");
        int q1 = md.indexOf("질문 하나");
        int a1 = md.indexOf("응답 하나");
        int q2 = md.indexOf("질문 둘");
        int a2 = md.indexOf("다시 쓴 응답 둘");
        assertThat(q1).isPositive();
        assertThat(q1).isLessThan(a1);
        assertThat(a1).isLessThan(q2);
        assertThat(q2).isLessThan(a2);
    }

    @Test
    @DisplayName("TC-CHT-085 메시지가 없는 세션 내보내기는 409 export_empty")
    void export_empty_session_is_conflict() {
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc("s1")).thenReturn(new ArrayList<>());

        assertRejected(() -> service.exportConversation(OWNER, "s1", "md", "Asia/Seoul"),
                HttpStatus.CONFLICT, "export_empty");
    }

    @Test
    @DisplayName("TC-CHT-085 남의 세션 내보내기는 404 session_not_found")
    void export_other_users_session_is_not_found() {
        assertRejected(() -> service.exportConversation("user-b", "s1", "md", "Asia/Seoul"),
                HttpStatus.NOT_FOUND, ChatSessionOwnership.SESSION_NOT_FOUND);
    }

    private static ConversationMessageEntity message(int seq, String role, String type, String content, String at) {
        ConversationMessageEntity m = new ConversationMessageEntity();
        m.setSessionId("s1");
        m.setSeq(seq);
        m.setRole(role);
        m.setMessageType(type);
        m.setContent(content);
        ReflectionTestUtils.setField(m, "createdAt", OffsetDateTime.parse(at));
        return m;
    }

    private static String docxText(byte[] bytes) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            return new XWPFWordExtractor(doc).getText();
        }
    }

    private static void assertRejected(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ChatRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getMessage()).isEqualTo(code);
                });
    }
}
