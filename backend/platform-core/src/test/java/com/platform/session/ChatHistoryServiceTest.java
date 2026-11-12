package com.platform.session;

import com.platform.config.PlatformSettingsService;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.domain.ChatProjectEntity;
import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ChatAttachmentRepository;
import com.platform.repository.ChatGeneratedImageRepository;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.repository.MessageFeedbackRepository;
import com.platform.share.ChatShareService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TC-CHT-041 ~ TC-CHT-044 (CR-178): 위젯 지난 대화 목록 조건, 이어보기 묶음과 표시, 대화 바꾸기.
 * 목록 SQL 의 대상 조건(TC-CHT-040)은 실제 DB 에서 API 로 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class ChatHistoryServiceTest {

    private static final String OWNER = "user-a";

    @Mock private ConversationSessionRepository sessionRepository;
    @Mock private ConversationMessageRepository messageRepository;
    @Mock private ChatAttachmentRepository attachmentRepository;
    @Mock private MessageFeedbackRepository feedbackRepository;
    @Mock private ChatProjectService projectService;
    @Mock private CancellationRegistry cancellationRegistry;
    @Mock private SessionStore sessionStore;
    @Mock private PlatformSettingsService settings;
    @Mock private ChatGeneratedImageRepository imageRepository;
    @Mock private ChatShareService shareService;

    private ChatHistoryService service;
    private int seq;

    @BeforeEach
    void setUp() {
        service = new ChatHistoryService(sessionRepository, messageRepository, attachmentRepository,
                feedbackRepository, projectService, cancellationRegistry, sessionStore, settings, imageRepository,
                shareService);
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(feedbackRepository.findByTurnIdInAndUserRef(any(), anyString())).thenReturn(List.of());
        lenient().when(sessionRepository.findWidgetHistory(anyString(), anyInt(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());
        seq = 0;
    }

    // TC-CHT-041

    @Test
    void list_with_project_shows_only_that_project() {
        UUID projectId = UUID.randomUUID();
        when(projectService.requireOwned(OWNER, projectId.toString())).thenReturn(project(projectId));

        service.list(OWNER, projectId.toString(), null, 0, null);

        verify(sessionRepository).findWidgetHistory(eq(OWNER), eq(1), eq(projectId.toString()), isNull(),
                any(Pageable.class));
    }

    @Test
    void list_with_none_shows_only_sessions_outside_projects() {
        service.list(OWNER, ChatHistoryService.NO_PROJECT, null, 0, null);

        verify(sessionRepository).findWidgetHistory(eq(OWNER), eq(2), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void list_without_condition_shows_everything_with_default_page_size() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        service.list(OWNER, null, null, 0, null);

        verify(sessionRepository).findWidgetHistory(eq(OWNER), eq(0), isNull(), isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(ChatHistoryService.DEFAULT_PAGE_SIZE);
    }

    @Test
    void list_with_query_searches_all_titles_regardless_of_project() {
        service.list(OWNER, UUID.randomUUID().toString(), "  50%_off  ", 0, 500);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(sessionRepository).findWidgetHistory(eq(OWNER), eq(0), isNull(), eq("%50\\%\\_off%"),
                pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(ChatHistoryService.MAX_PAGE_SIZE);
        verify(projectService, never()).requireOwned(anyString(), anyString());
    }

    @Test
    void list_row_carries_title_project_and_updated_time() {
        ConversationSessionEntity s = session(OWNER);
        UUID projectId = UUID.randomUUID();
        s.setChatProjectId(projectId);
        when(sessionRepository.findWidgetHistory(anyString(), anyInt(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(s)));

        Map<String, Object> row = service.list(OWNER, null, null, 0, null).getContent().get(0);

        assertThat(row).containsEntry("session_id", s.getSessionId()).containsEntry("title", "제목")
                .containsEntry("chat_project_id", projectId.toString()).containsKey("updated_at");
    }

    // TC-CHT-042

    @Test
    void messages_group_by_question_and_drop_system_tool_results_and_boundaries() {
        ConversationSessionEntity s = session(OWNER);
        UUID t1 = UUID.randomUUID();
        List<ConversationMessageEntity> rows = new ArrayList<>();
        rows.add(text("system", "screen context", t1));
        ConversationMessageEntity q1 = text("user", "질문 원문과 서버 안내", t1);
        q1.setDisplay(Map.of("text", "질문1"));
        rows.add(q1);
        rows.add(toolUse(t1, "search"));
        rows.add(row("user", ConversationMessageEntity.TYPE_TOOL_RESULT, "tool output", t1));
        rows.add(text("assistant", "답1a", t1));
        ConversationMessageEntity a1b = text("assistant", "답1b", t1);
        a1b.setDisplay(Map.of("tool_uses", List.of(Map.of("name", "web_fetch"))));
        a1b.setModel("claude-sonnet-5-5");
        rows.add(a1b);
        rows.add(row("system", ConversationMessageEntity.TYPE_COMPACT_BOUNDARY, "summary", null));
        rows.add(text("user", "질문2", null));
        rows.add(text("assistant", "답2", null));
        when(sessionRepository.findBySessionId(s.getSessionId())).thenReturn(Optional.of(s));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(s.getSessionId())).thenReturn(rows);

        Map<String, Object> result = service.messages(OWNER, s.getSessionId());

        List<Map<String, Object>> turns = turns(result);
        assertThat(turns).hasSize(2);
        Map<String, Object> first = turns.get(0);
        assertThat(first.get("turn_id")).isEqualTo(t1.toString());
        assertThat(first.get("user_seq")).isEqualTo(1);
        assertThat(question(first).get("text")).isEqualTo("질문1");
        Map<String, Object> answer = answer(first);
        assertThat(answer.get("text")).isEqualTo("답1a\n\n답1b");
        assertThat(answer.get("tool_uses")).isEqualTo(List.of(Map.of("name", "search"), Map.of("name", "web_fetch")));
        assertThat(answer.get("model")).isEqualTo("claude-sonnet-5-5");
        assertThat(answer.get("stopped")).isEqualTo(false);
        Map<String, Object> second = turns.get(1);
        assertThat(second.get("turn_id")).isNull();
        assertThat(second.get("user_seq")).isNull();
        assertThat(question(second).get("text")).isEqualTo("질문2");
        assertThat(result.get("truncated")).isEqualTo(false);
    }

    // TC-CHT-043

    @Test
    void stopped_answer_loses_marker_and_is_flagged() {
        ConversationSessionEntity s = ownedSession();
        UUID t = UUID.randomUUID();
        whenRows(s, text("user", "질문", t), text("assistant", ChatStopMarker.PREFIX + "부분 답", t));

        Map<String, Object> answer = answer(turns(service.messages(OWNER, s.getSessionId())).get(0));

        assertThat(answer.get("text")).isEqualTo("부분 답");
        assertThat(answer.get("stopped")).isEqualTo(true);
    }

    @Test
    void expired_attachment_keeps_name_but_is_unavailable() {
        ConversationSessionEntity s = ownedSession();
        UUID t = UUID.randomUUID();
        UUID expiredId = UUID.randomUUID();
        UUID liveId = UUID.randomUUID();
        ConversationMessageEntity q = text("user", "질문", t);
        q.setDisplay(Map.of("text", "질문", "attachments", List.of(
                Map.of("attachment_id", expiredId.toString(), "filename", "old.pdf", "media_type", "application/pdf"),
                Map.of("attachment_id", liveId.toString(), "filename", "new.png", "media_type", "image/png"))));
        whenRows(s, q, text("assistant", "답", t));
        ChatAttachmentEntity live = new ChatAttachmentEntity();
        live.setId(liveId);
        live.setExpiresAt(OffsetDateTime.now().plusDays(1));
        when(attachmentRepository.findAllById(any())).thenReturn(List.of(live));

        List<Map<String, Object>> attachments =
                (List<Map<String, Object>>) question(turns(service.messages(OWNER, s.getSessionId())).get(0))
                        .get("attachments");

        assertThat(attachments).hasSize(2);
        assertThat(attachments.get(0)).containsEntry("filename", "old.pdf")
                .containsEntry("media_type", "application/pdf").containsEntry("available", false);
        assertThat(attachments.get(1)).containsEntry("filename", "new.png").containsEntry("available", true);
    }

    @Test
    void someone_elses_session_is_not_found() {
        ConversationSessionEntity s = session("user-b");
        when(sessionRepository.findBySessionId(s.getSessionId())).thenReturn(Optional.of(s));

        assertRejected(() -> service.messages(OWNER, s.getSessionId()), HttpStatus.NOT_FOUND, "session_not_found");
        verify(messageRepository, never()).findBySessionIdOrderByCreatedAtAsc(anyString());
    }

    @Test
    void more_turns_than_limit_keep_the_latest_and_flag_truncated() {
        ConversationSessionEntity s = ownedSession();
        when(settings.getInt(eq("widget.history-turns-max"), anyInt())).thenReturn(2);
        List<ConversationMessageEntity> rows = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            UUID t = UUID.randomUUID();
            rows.add(text("user", "질문" + i, t));
            rows.add(text("assistant", "답" + i, t));
        }
        whenRows(s, rows.toArray(new ConversationMessageEntity[0]));

        Map<String, Object> result = service.messages(OWNER, s.getSessionId());

        assertThat(result.get("truncated")).isEqualTo(true);
        assertThat(turns(result)).extracting(t -> question(t).get("text")).containsExactly("질문2", "질문3");
    }

    // TC-CHT-044

    @Test
    void renaming_marks_title_as_user_written() {
        ConversationSessionEntity s = ownedSession();
        when(sessionRepository.saveAndFlush(s)).thenReturn(s);

        Map<String, Object> row = service.update(OWNER, s.getSessionId(), Map.of("title", "  새 이름  "));

        assertThat(s.getTitle()).isEqualTo("새 이름");
        assertThat(s.getTitleSource()).isEqualTo(ConversationSessionEntity.TITLE_SOURCE_USER);
        assertThat(row.get("title")).isEqualTo("새 이름");
    }

    @Test
    void blank_title_is_rejected() {
        ConversationSessionEntity s = ownedSession();

        assertRejected(() -> service.update(OWNER, s.getSessionId(), Map.of("title", "   ")),
                HttpStatus.BAD_REQUEST, "session_invalid:title");
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void moving_into_someone_elses_project_is_not_found() {
        ConversationSessionEntity s = ownedSession();
        String other = UUID.randomUUID().toString();
        when(projectService.requireOwned(OWNER, other)).thenThrow(ChatProjectService.projectNotFound());

        assertRejected(() -> service.update(OWNER, s.getSessionId(), Map.of("chat_project_id", other)),
                HttpStatus.NOT_FOUND, "project_not_found");
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void null_project_takes_the_session_out_of_its_project() {
        ConversationSessionEntity s = ownedSession();
        s.setChatProjectId(UUID.randomUUID());
        when(sessionRepository.saveAndFlush(s)).thenReturn(s);
        Map<String, Object> body = new HashMap<>();
        body.put("chat_project_id", null);

        service.update(OWNER, s.getSessionId(), body);

        assertThat(s.getChatProjectId()).isNull();
        assertThat(s.getTitleSource()).isNotEqualTo(ConversationSessionEntity.TITLE_SOURCE_USER);
    }

    @Test
    void delete_stops_answer_marks_rows_and_clears_cache() {
        ConversationSessionEntity s = ownedSession();

        service.delete(OWNER, s.getSessionId());

        assertThat(s.getDeletedAt()).isNotNull();
        verify(cancellationRegistry).cancel(s.getSessionId());
        verify(sessionRepository).save(s);
        verify(messageRepository).softDeleteBySessionId(eq(s.getSessionId()), any(OffsetDateTime.class));
        verify(imageRepository).softDeleteBySessionId(eq(s.getSessionId()), any(OffsetDateTime.class));
        // CR-182 TC-CHT-089: 지운 대화의 공유 링크를 끊는다
        verify(shareService).revokeAllForSession(s.getSessionId());
        verify(sessionStore).clearSession(s.getSessionId());
    }

    private ConversationSessionEntity ownedSession() {
        ConversationSessionEntity s = session(OWNER);
        when(sessionRepository.findBySessionId(s.getSessionId())).thenReturn(Optional.of(s));
        return s;
    }

    private void whenRows(ConversationSessionEntity s, ConversationMessageEntity... rows) {
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(s.getSessionId())).thenReturn(List.of(rows));
    }

    private static ConversationSessionEntity session(String owner) {
        ConversationSessionEntity s = new ConversationSessionEntity();
        s.setId(UUID.randomUUID());
        s.setSessionId("s-" + UUID.randomUUID());
        s.setUserId(owner);
        s.setTitle("제목");
        return s;
    }

    private static ChatProjectEntity project(UUID id) {
        ChatProjectEntity p = new ChatProjectEntity();
        p.setId(id);
        p.setOwnerRef(OWNER);
        p.setName("p");
        return p;
    }

    private ConversationMessageEntity text(String role, String content, UUID turnId) {
        return row(role, ConversationMessageEntity.TYPE_TEXT, content, turnId);
    }

    private ConversationMessageEntity toolUse(UUID turnId, String name) {
        ConversationMessageEntity m = row("assistant", ConversationMessageEntity.TYPE_TOOL_USE, null, turnId);
        m.setContentJson(List.of(Map.of("type", "tool_use", "name", name, "input", Map.of("q", "secret"))));
        return m;
    }

    private ConversationMessageEntity row(String role, String type, String content, UUID turnId) {
        ConversationMessageEntity m = new ConversationMessageEntity();
        m.setSeq(++seq);
        m.setRole(role);
        m.setMessageType(type);
        m.setContent(content);
        m.setTurnId(turnId);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> turns(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("turns");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> question(Map<String, Object> turn) {
        return (Map<String, Object>) turn.get("question");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> answer(Map<String, Object> turn) {
        return (Map<String, Object>) turn.get("answer");
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                       HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOf(ChatRejectedException.class).hasMessage(code)
                .extracting("status").isEqualTo(status);
    }
}
