package com.platform.session;

import com.platform.auth.UserPrincipal;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ChatAttachmentRepository;
import com.platform.repository.ChatGeneratedImageRepository;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.repository.MessageFeedbackRepository;
import com.platform.share.ChatShareService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
 * TC-CHT-146 부터 TC-CHT-150 (CR-194): 대화 접근 규칙, 함께 보는 대화 열기, 화면 키 목록, 묻는 사람, 되감기 제한.
 * 함께 보기 키의 부분 고유 색인과 목록 SQL 은 실제 DB 에서 API 로 확인한다(IT-24-003, IT-24-006).
 */
@ExtendWith(MockitoExtension.class)
class ChatScreenShareTest {

    private static final String KEY = "bo2:cs-inquiry:12345";
    private static final String OTHER_KEY = "bo2:cs-inquiry:99999";

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

    private ChatSessionOwnership ownership;
    private SharedConversationService shared;
    private ChatHistoryService history;
    private int seq;

    @BeforeEach
    void setUp() {
        ownership = new ChatSessionOwnership(sessionRepository);
        shared = new SharedConversationService(sessionRepository, messageRepository);
        history = new ChatHistoryService(sessionRepository, messageRepository, attachmentRepository,
                feedbackRepository, projectService, cancellationRegistry, sessionStore, settings, imageRepository,
                shareService);
        lenient().when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // TC-CHT-146 대화 접근

    @Test
    @DisplayName("TC-CHT-146 1 주인, 주인이 빈 대화, 같은 함께 보기 키는 들어가고 남과 다른 키는 404")
    void access_rule_by_owner_and_share_key() {
        ConversationSessionEntity mine = session("s-mine", "u-a", null);
        ConversationSessionEntity unowned = session("s-unowned", null, null);
        ConversationSessionEntity caseTalk = session("s-case", null, KEY);
        stubSessions(mine, unowned, caseTalk);

        signIn(widget("u-b", KEY));
        assertNotFound(() -> ownership.require("u-b", "s-mine"));
        assertThat(ownership.require("u-b", "s-unowned")).isSameAs(unowned);
        assertThat(ownership.require("u-b", "s-case")).isSameAs(caseTalk);

        signIn(widget("u-a", OTHER_KEY));
        assertThat(ownership.require("u-a", "s-mine")).isSameAs(mine);
        assertNotFound(() -> ownership.require("u-a", "s-case"));

        signIn(widget("u-a", null));
        assertNotFound(() -> ownership.require("u-a", "s-case"));
    }

    @Test
    @DisplayName("TC-CHT-146 2 대화 요청 확인은 아직 없는 대화 번호를 통과시키고, 로그인은 함께 보는 대화에 못 들어간다")
    void request_check_passes_new_number_and_blocks_login_on_shared() {
        stubSessions(session("s-case", null, KEY), session("s-mine", "user-1", null));
        when(sessionRepository.findBySessionId("s-new")).thenReturn(Optional.empty());

        signIn(login());
        assertThatCode(() -> ownership.requireIfExists("s-new")).doesNotThrowAnyException();
        assertThatCode(() -> ownership.requireIfExists("s-mine")).doesNotThrowAnyException();
        assertNotFound(() -> ownership.requireIfExists("s-case"));

        signIn(widget("u-b", KEY));
        assertThatCode(() -> ownership.requireIfExists("s-case")).doesNotThrowAnyException();
        assertNotFound(() -> ownership.requireIfExists("s-mine"));
    }

    @Test
    @DisplayName("TC-CHT-146 3 이어보기는 주인이거나 같은 함께 보기 키여야 한다")
    void history_messages_follow_the_rule() {
        stubSessions(session("s-case", null, KEY), session("s-mine", "u-a", null));
        lenient().when(messageRepository.findBySessionIdOrderByCreatedAtAsc(anyString())).thenReturn(List.of());

        signIn(widget("u-b", KEY));
        assertThat(history.messages("u-b", "s-case").get("shared")).isEqualTo(true);
        assertNotFound(() -> history.messages("u-b", "s-mine"));

        signIn(widget("u-a", null));
        assertThat(history.messages("u-a", "s-mine").get("shared")).isEqualTo(false);
        assertNotFound(() -> history.messages("u-a", "s-case"));
    }

    // TC-CHT-147 함께 보는 대화 열기

    @Test
    @DisplayName("TC-CHT-147 1 처음이면 만들고 두 번째는 같은 번호")
    void open_creates_once_then_returns_same() {
        when(sessionRepository.findLiveByShareKey(KEY)).thenReturn(Optional.empty());
        when(sessionRepository.insertSharedIfAbsent(any(UUID.class), anyString(), eq(KEY))).thenReturn(1);
        SharedConversationService.Opened first = shared.open(KEY);
        assertThat(first.created()).isTrue();

        when(sessionRepository.findLiveByShareKey(KEY))
                .thenReturn(Optional.of(session(first.sessionId(), null, KEY)));
        SharedConversationService.Opened second = shared.open(KEY);
        assertThat(second.created()).isFalse();
        assertThat(second.sessionId()).isEqualTo(first.sessionId());
    }

    @Test
    @DisplayName("TC-CHT-147 2 동시에 와서 늦은 쪽이 만들지 못하면 먼저 만든 대화를 받는다")
    void open_race_returns_winner() {
        when(sessionRepository.findLiveByShareKey(KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(session("s-winner", null, KEY)));
        when(sessionRepository.insertSharedIfAbsent(any(UUID.class), anyString(), eq(KEY))).thenReturn(0);

        SharedConversationService.Opened opened = shared.open(KEY);
        assertThat(opened.sessionId()).isEqualTo("s-winner");
        assertThat(opened.created()).isFalse();
    }

    @Test
    @DisplayName("TC-CHT-147 3 함께 보기 키 없는 토큰은 400 share_key_required")
    void open_without_key_is_rejected() {
        assertThatThrownBy(() -> shared.open(null)).isInstanceOf(ChatRejectedException.class)
                .hasMessage("share_key_required").extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        verify(sessionRepository, never()).insertSharedIfAbsent(any(), any(), any());
    }

    // TC-CHT-148 대화 목록 화면 키

    @Test
    @DisplayName("TC-CHT-148 화면 키를 목록 조건으로 넘기고, 다른 조건과 함께 쓴다")
    void list_passes_screen_key() {
        lenient().when(sessionRepository.findWidgetHistory(anyString(), anyInt(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        history.list("u-a", null, null, "bo2:orders", 0, null);
        verify(sessionRepository).findWidgetHistory(eq("u-a"), eq(0), isNull(), isNull(), eq("bo2:orders"),
                any(Pageable.class));

        history.list("u-a", null, null, " ", 0, null);
        verify(sessionRepository).findWidgetHistory(eq("u-a"), eq(0), isNull(), isNull(), isNull(),
                any(Pageable.class));
    }

    // TC-CHT-149 묻는 사람

    @Test
    @DisplayName("TC-CHT-149 이어보기 질문에 질문 당시 묻는 사람이 나오고, 남기지 않은 질문에는 칸이 없다")
    void history_shows_asker_from_display() {
        stubSessions(session("s-case", null, KEY));
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc("s-case")).thenReturn(List.of(
                question("앞 질문", t1, null, null), text("assistant", "답", t1),
                question("김 질문", t2, "u-a", "cs_kim"), text("assistant", "답", t2),
                question("이름 없는 질문", t3, "u-b", null), text("assistant", "답", t3)));

        signIn(widget("u-b", KEY));
        List<Map<String, Object>> turns = turns(history.messages("u-b", "s-case"));

        assertThat(questionOf(turns.get(0))).doesNotContainKey("asker");
        assertThat(questionOf(turns.get(1)).get("asker")).isEqualTo(Map.of("user_ref", "u-a", "user_name", "cs_kim"));
        Map<String, Object> noName = new HashMap<>();
        noName.put("user_ref", "u-b");
        noName.put("user_name", null);
        assertThat(questionOf(turns.get(2)).get("asker")).isEqualTo(noName);
    }

    // TC-CHT-150 함께 보는 대화 되감기, 이름 바꾸기와 삭제

    @Test
    @DisplayName("TC-CHT-150 1 마지막 질문을 한 사람만 그 질문을 되감는다")
    void shared_rewind_only_last_turn_by_its_asker() {
        ConversationSessionEntity caseTalk = session("s-case", null, KEY);
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        List<ConversationMessageEntity> rows = List.of(
                question("A 질문", t1, "u-a", "cs_kim"), text("assistant", "답", t1),
                context(t2), question("B 질문", t2, "u-b", "cs_lee"), text("assistant", "답", t2));
        when(sessionRepository.findBySessionId("s-case")).thenReturn(Optional.of(caseTalk));
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc("s-case")).thenReturn(rows);
        int lastTurnStart = rows.get(2).getSeq();

        assertThatCode(() -> shared.checkRewind("s-case", lastTurnStart, "u-b")).doesNotThrowAnyException();
        assertConflict(() -> shared.checkRewind("s-case", lastTurnStart, "u-a"));
        assertConflict(() -> shared.checkRewind("s-case", rows.get(0).getSeq(), "u-a"));
    }

    @Test
    @DisplayName("TC-CHT-150 2 함께 보는 대화가 아니면 되감기를 막지 않는다")
    void rewind_rule_skips_ordinary_conversation() {
        when(sessionRepository.findBySessionId("s-mine")).thenReturn(Optional.of(session("s-mine", "u-a", null)));

        assertThatCode(() -> shared.checkRewind("s-mine", 0, "u-a")).doesNotThrowAnyException();
        verify(messageRepository, never()).findBySessionIdOrderByCreatedAtAsc(anyString());
    }

    @Test
    @DisplayName("TC-CHT-150 3 함께 보는 대화의 이름 바꾸기와 삭제는 404")
    void shared_rename_and_delete_are_not_found() {
        stubSessions(session("s-case", null, KEY));
        signIn(widget("u-b", KEY));

        assertNotFound(() -> history.update("u-b", "s-case", Map.of("title", "새 이름")));
        assertNotFound(() -> history.delete("u-b", "s-case"));
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    private void stubSessions(ConversationSessionEntity... sessions) {
        for (ConversationSessionEntity s : sessions) {
            lenient().when(sessionRepository.findBySessionId(s.getSessionId())).thenReturn(Optional.of(s));
        }
    }

    private static ConversationSessionEntity session(String sessionId, String owner, String shareKey) {
        ConversationSessionEntity s = new ConversationSessionEntity();
        s.setId(UUID.randomUUID());
        s.setSessionId(sessionId);
        s.setUserId(owner);
        s.setShareKey(shareKey);
        s.setScreenKey(shareKey);
        s.setTitle("제목");
        return s;
    }

    private ConversationMessageEntity question(String content, UUID turnId, String askerRef, String askerName) {
        ConversationMessageEntity m = text("user", content, turnId);
        Map<String, Object> display = new HashMap<>();
        display.put("text", content);
        display.put("attachments", new ArrayList<>());
        if (askerRef != null) {
            Map<String, Object> asker = new HashMap<>();
            asker.put("user_ref", askerRef);
            if (askerName != null) asker.put("user_name", askerName);
            display.put("asker", asker);
        }
        m.setDisplay(display);
        return m;
    }

    /** 붙이는 앱이 질문 앞에 보낸 화면 맥락 줄. 질문 묶음의 첫 순번이다. */
    private ConversationMessageEntity context(UUID turnId) {
        return text("user", "[화면 맥락]", turnId);
    }

    private ConversationMessageEntity text(String role, String content, UUID turnId) {
        ConversationMessageEntity m = new ConversationMessageEntity();
        m.setSeq(++seq);
        m.setRole(role);
        m.setMessageType(ConversationMessageEntity.TYPE_TEXT);
        m.setContent(content);
        m.setTurnId(turnId);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> turns(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("turns");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> questionOf(Map<String, Object> turn) {
        return (Map<String, Object>) turn.get("question");
    }

    private static UserPrincipal widget(String userRef, String shareKey) {
        Map<String, Object> info = new HashMap<>();
        if (userRef != null) info.put(ChatOwner.USER_REF_KEY, userRef);
        if (shareKey != null) info.put(ChatOwner.SHARE_KEY_KEY, shareKey);
        String id = userRef != null ? userRef : "widget";
        return new UserPrincipal(id, id, "tenant-a", ChatOwner.WIDGET_ROLE, info);
    }

    private static UserPrincipal login() {
        return new UserPrincipal("user-1", "a@example.com", "tenant-a", "ADMIN", Map.of());
    }

    private static void signIn(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ChatRejectedException.class).hasMessage("session_not_found")
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ChatRejectedException.class).hasMessage("rewind_rejected:shared")
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }
}
