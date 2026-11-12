package com.platform.share;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.artifact.ArtifactService;
import com.platform.config.PlatformSettingsService;
import com.platform.config.WorkspaceProperties;
import com.platform.document.DocumentFonts;
import com.platform.document.DocumentRenderer;
import com.platform.domain.ChatShareEntity;
import com.platform.domain.ConversationMessageEntity;
import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ChatShareRepository;
import com.platform.repository.ConversationMessageRepository;
import com.platform.repository.ConversationSessionRepository;
import com.platform.session.ChatExportLabels;
import com.platform.session.ChatExportService;
import com.platform.session.ChatRejectedException;
import com.platform.session.ChatSessionOwnership;
import com.platform.session.ChatTurnLookup;
import com.platform.session.SessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CR-182 TC-CHT-086, TC-CHT-087, TC-CHT-089: 공유 링크 만들기, 사본 고정, 끊기와 지우기. */
class ChatShareServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T01:00:00Z");
    private static final String OWNER = "user-a";
    private static final String TENANT = "t1";

    @TempDir Path root;

    private Path ws;
    private Path base;
    private byte[] fileBytes;
    private PlatformSettingsService platformSettings;
    private ChatShareRepository repository;
    private final List<ChatShareEntity> rows = new ArrayList<>();
    private final List<ConversationMessageEntity> messages = new ArrayList<>();
    private ChatSessionOwnership ownership;
    private ChatExportService exportService;
    private ChatExportLabels labels;
    private DocumentRenderer renderer;
    private ArtifactService artifactService;
    private WorkspaceProperties props;
    private ChatShareService service;

    @BeforeEach
    void setUp() throws IOException {
        ws = Files.createDirectories(root.resolve("ws"));
        Path out = Files.createDirectories(ws.resolve("out"));
        fileBytes = "docx 바이트 그대로".getBytes(StandardCharsets.UTF_8);
        Files.write(out.resolve("보고서.docx"), fileBytes);
        base = root.resolve("base");

        platformSettings = mock(PlatformSettingsService.class);
        when(platformSettings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(platformSettings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(platformSettings.getString(eq(ChatShareSettings.PUBLIC_BASE_URL_KEY), any()))
                .thenReturn("https://share.example.com/");

        repository = mock(ChatShareRepository.class);
        // 저장소는 메모리 목록으로 흉내 낸다. 질의 조건은 ChatShareRepository 메서드 이름 그대로다.
        when(repository.save(any(ChatShareEntity.class))).thenAnswer(inv -> {
            ChatShareEntity e = inv.getArgument(0);
            rows.add(e);
            return e;
        });
        when(repository.findById(any(UUID.class))).thenAnswer(inv ->
                rows.stream().filter(s -> s.getId().equals(inv.getArgument(0))).findFirst());
        when(repository.findByToken(anyString())).thenAnswer(inv ->
                rows.stream().filter(s -> s.getToken().equals(inv.getArgument(0))).findFirst());
        when(repository.findBySessionIdAndRevokedAtIsNull(anyString())).thenAnswer(inv ->
                rows.stream().filter(s -> s.getSessionId().equals(inv.getArgument(0)) && s.getRevokedAt() == null)
                        .toList());
        when(repository.findByExpiresAtBeforeAndFilesPurgedAtIsNull(any(OffsetDateTime.class))).thenAnswer(inv -> {
            OffsetDateTime now = inv.getArgument(0);
            return rows.stream().filter(s -> s.getExpiresAt().isBefore(now) && s.getFilesPurgedAt() == null).toList();
        });

        ConversationSessionRepository sessionRepository = mock(ConversationSessionRepository.class);
        when(sessionRepository.findBySessionId("s1")).thenReturn(Optional.of(session("s1")));
        when(sessionRepository.findBySessionId("s2")).thenReturn(Optional.of(session("s2")));
        ConversationMessageRepository messageRepository = mock(ConversationMessageRepository.class);
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(anyString()))
                .thenAnswer(inv -> new ArrayList<>(messages));
        messages.add(message(1, "user", "질문 하나", "2026-11-13T00:50:00Z"));
        messages.add(message(2, "assistant", "응답 하나", "2026-11-13T00:51:00Z"));

        SessionStore sessionStore = mock(SessionStore.class);
        when(sessionStore.getWorkspaceRef(anyString())).thenReturn(ws.toString());
        props = new WorkspaceProperties();
        props.setBase(base.toString());
        props.setWhitelistRoots(List.of(root.toString()));

        ownership = new ChatSessionOwnership(sessionRepository);
        renderer = new DocumentRenderer(new DocumentFonts(null));
        labels = new ChatExportLabels(platformSettings, new ObjectMapper());
        exportService = new ChatExportService(ownership, new ChatTurnLookup(sessionRepository, messageRepository),
                messageRepository, renderer, labels, platformSettings);
        artifactService = new ArtifactService(sessionStore, props);
        service = serviceAt(NOW);
    }

    @Test
    @DisplayName("TC-CHT-086 CONVERSATION 링크는 사본 파일과 행을 만들고 url 에 tenant 와 43자 token, 기한 7일")
    void create_conversation_share() throws IOException {
        Map<String, Object> view = service.create(OWNER, TENANT, "s1", "conversation", null, null);

        assertThat(rows).hasSize(1);
        ChatShareEntity row = rows.get(0);
        assertThat(view.get("share_id")).isEqualTo(row.getId().toString());
        assertThat(view.get("kind")).isEqualTo("CONVERSATION");
        assertThat(view.get("status")).isEqualTo("ACTIVE");
        assertThat(view.get("view_count")).isEqualTo(0);
        String url = (String) view.get("url");
        assertThat(url).startsWith("https://share.example.com/share/t1/");
        String token = url.substring(url.lastIndexOf('/') + 1);
        assertThat(token).matches("[A-Za-z0-9_-]{43}").isEqualTo(row.getToken());
        assertThat(OffsetDateTime.parse((String) view.get("expires_at")).toInstant())
                .isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(row.getCreatedBy()).isEqualTo(OWNER);

        Path snapshot = Path.of(row.getSnapshotPath());
        assertThat(snapshot).isEqualTo(base.resolve("shares").resolve(TENANT).resolve(row.getId().toString())
                .resolve(ChatShareService.CONVERSATION_FILE).toAbsolutePath().normalize());
        assertThat(snapshot).isRegularFile();
        assertThat(row.getSnapshotSize()).isEqualTo(Files.size(snapshot));
        assertThat(row.getContentType()).startsWith("text/html");
        String html = Files.readString(snapshot);
        assertThat(html).contains("질문 하나").contains("응답 하나");
        assertThat(html).contains("공유한 대화 (기한 2026-11-20 10:00)");
    }

    @Test
    @DisplayName("TC-CHT-086 FILE 링크 사본은 out 파일과 같은 바이트다")
    void create_file_share_copies_same_bytes() throws IOException {
        Map<String, Object> view = service.create(OWNER, TENANT, "s1", "FILE", "보고서.docx", 3);

        ChatShareEntity row = rows.get(0);
        assertThat(view.get("kind")).isEqualTo("FILE");
        assertThat(view.get("file_name")).isEqualTo("보고서.docx");
        assertThat(OffsetDateTime.parse((String) view.get("expires_at")).toInstant())
                .isEqualTo(NOW.plus(Duration.ofDays(3)));
        assertThat(Files.readAllBytes(Path.of(row.getSnapshotPath()))).isEqualTo(fileBytes);
        assertThat(row.getContentType()).contains("wordprocessingml");
    }

    @Test
    @DisplayName("TC-CHT-086 공개 주소가 비어 있으면 409 share_not_configured")
    void empty_public_url_is_not_configured() {
        when(platformSettings.getString(eq(ChatShareSettings.PUBLIC_BASE_URL_KEY), any())).thenReturn("  ");

        assertRejected(() -> service.create(OWNER, TENANT, "s1", "CONVERSATION", null, null),
                HttpStatus.CONFLICT, ChatShareService.SHARE_NOT_CONFIGURED);
        assertNothingWritten();
    }

    @Test
    @DisplayName("TC-CHT-086 ttl_days 가 1..최대 밖이면 400 share_invalid:ttl_days")
    void ttl_out_of_range_is_bad_request() {
        assertRejected(() -> service.create(OWNER, TENANT, "s1", "CONVERSATION", null, 31),
                HttpStatus.BAD_REQUEST, ChatShareService.INVALID_TTL);
        assertRejected(() -> service.create(OWNER, TENANT, "s1", "CONVERSATION", null, 0),
                HttpStatus.BAD_REQUEST, ChatShareService.INVALID_TTL);
        assertNothingWritten();
    }

    @Test
    @DisplayName("TC-CHT-086 남의 세션은 404 session_not_found")
    void other_users_session_is_not_found() {
        assertRejected(() -> service.create("user-b", TENANT, "s1", "CONVERSATION", null, null),
                HttpStatus.NOT_FOUND, ChatSessionOwnership.SESSION_NOT_FOUND);
        assertNothingWritten();
    }

    @Test
    @DisplayName("TC-CHT-086 틀린 kind, 이름 없는 FILE, 없는 파일, 빈 대화는 각자의 코드로 거절하고 폴더를 남기지 않는다")
    void invalid_requests_are_rejected_without_leftovers() throws IOException {
        assertRejected(() -> service.create(OWNER, TENANT, "s1", "LINK", null, null),
                HttpStatus.BAD_REQUEST, ChatShareService.INVALID_KIND);
        assertRejected(() -> service.create(OWNER, TENANT, "s1", "FILE", " ", null),
                HttpStatus.BAD_REQUEST, ChatShareService.INVALID_FILE_NAME);
        assertRejected(() -> service.create(OWNER, TENANT, "s1", "FILE", "없음.docx", null),
                HttpStatus.NOT_FOUND, ChatShareService.FILE_NOT_FOUND);
        messages.clear();
        assertRejected(() -> service.create(OWNER, TENANT, "s1", "CONVERSATION", null, null),
                HttpStatus.CONFLICT, "export_empty");

        verify(repository, never()).save(any(ChatShareEntity.class));
        Path tenantDir = base.resolve("shares").resolve(TENANT);
        if (Files.exists(tenantDir)) {
            try (Stream<Path> left = Files.list(tenantDir)) {
                assertThat(left).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("TC-CHT-087 만든 뒤 대화가 이어지고 out 파일이 지워져도 링크는 만든 순간 내용을 연다")
    void snapshot_stays_fixed() throws IOException {
        service.create(OWNER, TENANT, "s1", "CONVERSATION", null, null);
        service.create(OWNER, TENANT, "s1", "FILE", "보고서.docx", null);
        ChatShareEntity conversation = rows.get(0);
        ChatShareEntity file = rows.get(1);

        messages.add(message(3, "user", "질문 셋", "2026-11-13T00:59:00Z"));
        Files.delete(ws.resolve("out").resolve("보고서.docx"));

        ChatShareService.Opened openedConversation = service.open(conversation.getToken()).orElseThrow();
        String html = Files.readString(openedConversation.path());
        assertThat(html).contains("질문 하나").doesNotContain("질문 셋");
        assertThat(openedConversation.kind()).isEqualTo(ChatShareEntity.Kind.CONVERSATION);

        ChatShareService.Opened openedFile = service.open(file.getToken()).orElseThrow();
        assertThat(openedFile.fileName()).isEqualTo("보고서.docx");
        assertThat(Files.readAllBytes(openedFile.path())).isEqualTo(fileBytes);
        verify(repository).countView(eq(conversation.getId()), any(OffsetDateTime.class));
        verify(repository).countView(eq(file.getId()), any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("TC-CHT-089 주인이 끊으면 revoked_at 이 서고 사본 파일이 없어지며 더 열리지 않는다")
    void owner_revoke_removes_snapshot() {
        service.create(OWNER, TENANT, "s1", "FILE", "보고서.docx", null);
        ChatShareEntity row = rows.get(0);
        Path dir = Path.of(row.getSnapshotPath()).getParent();

        Map<String, Object> view = service.revoke(OWNER, TENANT, row.getId().toString());

        assertThat(view.get("status")).isEqualTo("REVOKED");
        assertThat(view.get("revoked_at")).isNotNull();
        assertThat(row.getRevokedAt().toInstant()).isEqualTo(NOW);
        assertThat(row.getFilesPurgedAt().toInstant()).isEqualTo(NOW);
        assertThat(dir).doesNotExist();
        assertThat(service.open(row.getToken())).isEmpty();
    }

    @Test
    @DisplayName("TC-CHT-089 남이 끊으면 404 share_not_found 이고 링크는 그대로다")
    void other_user_revoke_is_not_found() {
        service.create(OWNER, TENANT, "s1", "FILE", "보고서.docx", null);
        ChatShareEntity row = rows.get(0);

        assertRejected(() -> service.revoke("user-b", TENANT, row.getId().toString()),
                HttpStatus.NOT_FOUND, ChatShareService.SHARE_NOT_FOUND);
        assertRejected(() -> service.revoke(OWNER, TENANT, "not-a-uuid"),
                HttpStatus.NOT_FOUND, ChatShareService.SHARE_NOT_FOUND);
        assertThat(row.getRevokedAt()).isNull();
        assertThat(Path.of(row.getSnapshotPath())).isRegularFile();
    }

    @Test
    @DisplayName("TC-CHT-089 기한 지난 링크에 지우기 작업을 돌리면 files_purged_at 이 서고 파일이 없어진다")
    void purge_expired_removes_files_and_keeps_row() {
        service.create(OWNER, TENANT, "s1", "FILE", "보고서.docx", 1);
        service.create(OWNER, TENANT, "s1", "CONVERSATION", null, 7);
        ChatShareEntity expired = rows.get(0);
        ChatShareEntity live = rows.get(1);
        Instant later = NOW.plus(Duration.ofDays(2));

        int purged = serviceAt(later).purgeExpired();

        assertThat(purged).isEqualTo(1);
        assertThat(expired.getFilesPurgedAt().toInstant()).isEqualTo(later);
        assertThat(expired.getRevokedAt()).isNull();
        assertThat(Path.of(expired.getSnapshotPath()).getParent()).doesNotExist();
        assertThat(live.getFilesPurgedAt()).isNull();
        assertThat(Path.of(live.getSnapshotPath())).isRegularFile();
        assertThat(serviceAt(later).open(expired.getToken())).isEmpty();
    }

    @Test
    @DisplayName("TC-CHT-089 세션을 지우면 그 세션의 살아 있는 링크를 모두 끊는다")
    void session_delete_revokes_all_its_links() {
        service.create(OWNER, TENANT, "s1", "CONVERSATION", null, null);
        service.create(OWNER, TENANT, "s1", "FILE", "보고서.docx", null);
        service.create(OWNER, TENANT, "s2", "CONVERSATION", null, null);

        int revoked = service.revokeAllForSession("s1");

        assertThat(revoked).isEqualTo(2);
        for (ChatShareEntity row : rows.subList(0, 2)) {
            assertThat(row.getRevokedAt()).isNotNull();
            assertThat(Path.of(row.getSnapshotPath()).getParent()).doesNotExist();
        }
        ChatShareEntity other = rows.get(2);
        assertThat(other.getRevokedAt()).isNull();
        assertThat(Path.of(other.getSnapshotPath())).isRegularFile();
        verify(repository, times(3)).save(any(ChatShareEntity.class));
    }

    private ChatShareService serviceAt(Instant now) {
        return new ChatShareService(repository, ownership, exportService, labels, renderer, artifactService, props,
                new ChatShareSettings(platformSettings), Clock.fixed(now, ZoneOffset.UTC));
    }

    private void assertNothingWritten() {
        verify(repository, never()).save(any(ChatShareEntity.class));
        assertThat(base.resolve("shares")).doesNotExist();
    }

    private static ConversationSessionEntity session(String id) {
        ConversationSessionEntity s = new ConversationSessionEntity();
        s.setSessionId(id);
        s.setUserId(OWNER);
        s.setTitle("3분기 반품");
        return s;
    }

    private static ConversationMessageEntity message(int seq, String role, String content, String at) {
        ConversationMessageEntity m = new ConversationMessageEntity();
        m.setSessionId("s1");
        m.setSeq(seq);
        m.setRole(role);
        m.setMessageType(ConversationMessageEntity.TYPE_TEXT);
        m.setContent(content);
        ReflectionTestUtils.setField(m, "createdAt", OffsetDateTime.parse(at));
        return m;
    }

    private static void assertRejected(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ChatRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getMessage()).isEqualTo(code);
                });
    }
}
