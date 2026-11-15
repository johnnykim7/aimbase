package com.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.auth.JwtProvider;
import com.platform.auth.UserPrincipal;
import com.platform.config.PlatformSettingsService;
import com.platform.orchestrator.ChatRequest;
import com.platform.session.ChatOwner;
import com.platform.session.ChatRejectedException;
import com.platform.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TC-CHT-145, TC-CHT-146, TC-CHT-149 (CR-194): 위젯 토큰 발급 칸, 대화 요청을 응답 전에 거르기, 질문에 묻는 사람과
 * 화면 키를 실어 보내기.
 */
class ChatScreenShareApiTest {

    private static final String SECRET = "cr194-screen-share-test-secret-which-is-long-enough-1234";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class TokenFields {

        @Mock private PlatformSettingsService settings;
        @Mock private HttpServletRequest http;

        private final JwtProvider jwtProvider = new JwtProvider(SECRET, 1800_000L, 604800_000L);

        @Test
        @DisplayName("TC-CHT-145 1 user_ref, user_name, share_key 를 주면 토큰에 실린다")
        void issues_token_with_user_name_and_share_key() {
            Claims claims = jwtProvider.extractClaims(issue(request("123", "cs_kim", "bo2:cs-inquiry:12345")));
            assertThat(claims.get("user_ref")).isEqualTo("123");
            assertThat(claims.get("user_name")).isEqualTo("cs_kim");
            assertThat(claims.get("share_key")).isEqualTo("bo2:cs-inquiry:12345");
        }

        @Test
        @DisplayName("TC-CHT-145 2 모르는 칸이 와도 요청을 읽는다")
        void ignores_unknown_fields() {
            assertThatCode(() -> new ObjectMapper().readValue(
                    "{\"origin\":\"https://oms.com\",\"user_ref\":\"123\",\"later_field\":1}",
                    WidgetTokenController.IssueRequest.class)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("TC-CHT-145 3 글자 규칙, user_ref 없는 함께 보기 키, 긴 이름은 400")
        void rejects_invalid_fields() {
            assertRejected(() -> issue(request("123", null, "bo2:cs inquiry")),
                    HttpStatus.BAD_REQUEST, "widget_token_invalid:share_key");
            assertRejected(() -> issue(request(null, null, "bo2:cs-inquiry:1")),
                    HttpStatus.BAD_REQUEST, "widget_token_invalid:user_ref");
            assertRejected(() -> issue(request("123", "a".repeat(101), null)),
                    HttpStatus.BAD_REQUEST, "widget_token_invalid:user_name");
            assertRejected(() -> issue(request("123", null, "k".repeat(129))),
                    HttpStatus.BAD_REQUEST, "widget_token_invalid:share_key");
        }

        private String issue(WidgetTokenController.IssueRequest req) {
            TenantContext.setTenantId("tenant-abc");
            lenient().when(http.getHeader("X-API-Key")).thenReturn("test-api-key");
            lenient().when(settings.getStringList("widget.allowed-origins", List.of()))
                    .thenReturn(List.of("https://oms.com"));
            lenient().when(settings.getStringList(eq("widget.allowed-scopes"), any())).thenReturn(List.of("chat:stream"));
            lenient().when(settings.getInt("widget.token-ttl-seconds", 1800)).thenReturn(1800);
            lenient().when(settings.getInt("widget.token-max-ttl-seconds", 3600)).thenReturn(3600);
            ResponseEntity<?> response = new WidgetTokenController(jwtProvider, settings).issueWidgetToken(req, http);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) ((ApiResponse<?>) Objects.requireNonNull(response.getBody())).data();
            return (String) body.get("token");
        }

        private static WidgetTokenController.IssueRequest request(String userRef, String userName, String shareKey) {
            WidgetTokenController.IssueRequest req = new WidgetTokenController.IssueRequest();
            req.origin = "https://oms.com";
            req.userRef = userRef;
            req.userName = userName;
            req.shareKey = shareKey;
            return req;
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Completions {

        @Mock private com.platform.orchestrator.OrchestratorEngine orchestrator;
        @Mock private com.platform.config.WorkspaceProperties workspaceProperties;
        @Mock private com.platform.session.CancellationRegistry cancellationRegistry;
        @Mock private com.platform.attachment.AttachmentService attachmentService;
        @Mock private com.platform.attachment.PdfTextExtractor pdfTextExtractor;
        @Mock private com.platform.attachment.PdfVisionResolver pdfVisionResolver;
        @Mock private com.platform.llm.LLMAdapterRegistry adapterRegistry;
        @Mock private com.platform.artifact.ArtifactService artifactService;
        @Mock private com.platform.llm.ConnectionAdapterFactory connectionAdapterFactory;
        @Mock private com.platform.session.ConversationRewindService rewindService;
        @Mock private com.platform.session.ChatWidgetSettingsService widgetSettings;
        @Mock private com.platform.session.ChatProjectService chatProjectService;
        @Mock private com.platform.repository.ConversationSessionRepository sessionRepository;
        @Mock private com.platform.session.ChatSessionOwnership sessionAccess;
        @Mock private com.platform.session.SharedConversationService sharedConversations;
        @Mock private com.platform.chatdoc.DocumentEditService documentEditService;
        @InjectMocks private ChatController chatController;

        @Test
        @DisplayName("TC-CHT-146 4 들어갈 수 없는 대화의 요청은 응답을 시작하기 전에 404")
        void rejects_before_answering() {
            signIn(widget("u-b", null, null));
            doThrow(new ChatRejectedException(HttpStatus.NOT_FOUND, "session_not_found"))
                    .when(sessionAccess).requireIfExists("s-other");

            assertRejected(() -> chatController.completions(request("s-other", null, true)),
                    HttpStatus.NOT_FOUND, "session_not_found");
            verifyNoInteractions(orchestrator, rewindService);
        }

        @Test
        @DisplayName("TC-CHT-146 5 화면 키 글자 규칙에 맞지 않으면 400 session_invalid:screen_key")
        void rejects_invalid_screen_key() {
            signIn(widget("u-a", null, null));
            assertRejected(() -> chatController.completions(request("s-new", "bo2 orders", true)),
                    HttpStatus.BAD_REQUEST, "session_invalid:screen_key");
            verifyNoInteractions(orchestrator);
        }

        @Test
        @DisplayName("TC-CHT-149 질문에 묻는 사람과 화면 키를 실어 저장으로 보낸다")
        void sends_asker_and_screen_key() {
            signIn(widget("u-a", "cs_kim", "bo2:cs-inquiry:12345"));
            ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
            when(orchestrator.chat(sent.capture())).thenThrow(new UnsupportedOperationException("stop"));

            // 저장으로 보낸 요청만 본다. 모델을 부르는 자리에서 멈춘다
            org.assertj.core.api.Assertions.catchThrowable(() -> chatController.completions(request("s-new", "bo2:orders", false)));

            ChatRequest.WidgetTurn turn = sent.getValue().widgetTurn();
            assertThat(turn.screenKey()).isEqualTo("bo2:orders");
            assertThat(turn.questionDisplay().get("asker"))
                    .isEqualTo(Map.of("user_ref", "u-a", "user_name", "cs_kim"));
        }

        @Test
        @DisplayName("TC-CHT-149 user_ref 없는 토큰의 질문에는 묻는 사람이 없다")
        void no_asker_without_user_ref() {
            signIn(widget(null, null, null));
            ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
            when(orchestrator.chat(sent.capture())).thenThrow(new UnsupportedOperationException("stop"));

            // 저장으로 보낸 요청만 본다. 모델을 부르는 자리에서 멈춘다
            org.assertj.core.api.Assertions.catchThrowable(() -> chatController.completions(request("s-new", null, false)));

            assertThat(sent.getValue().widgetTurn().questionDisplay()).doesNotContainKey("asker");
        }

        private static ChatController.ChatCompletionRequest request(String sessionId, String screenKey, boolean stream) {
            return new ChatController.ChatCompletionRequest(
                    null, sessionId, List.of(new ChatController.MessageDto("user", "질문")),
                    stream, false, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, screenKey);
        }
    }

    private static UserPrincipal widget(String userRef, String userName, String shareKey) {
        Map<String, Object> info = new HashMap<>();
        if (userRef != null) info.put(ChatOwner.USER_REF_KEY, userRef);
        if (userName != null) info.put(ChatOwner.USER_NAME_KEY, userName);
        if (shareKey != null) info.put(ChatOwner.SHARE_KEY_KEY, shareKey);
        String id = userRef != null ? userRef : "widget";
        return new UserPrincipal(id, id, "tenant-a", ChatOwner.WIDGET_ROLE, info);
    }

    private static void signIn(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                       HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOf(ChatRejectedException.class).hasMessage(code)
                .extracting("status").isEqualTo(status);
    }
}
