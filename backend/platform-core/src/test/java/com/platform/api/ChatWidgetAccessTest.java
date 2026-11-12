package com.platform.api;

import com.platform.auth.UserPrincipal;
import com.platform.session.ChatOwner;
import com.platform.session.ChatProjectService;
import com.platform.session.ChatRejectedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TC-CHT-049 (CR-178 BIZ-170, PRD-427): 위젯 토큰의 관리용 대화 API 차단, user_ref 없는 위젯 토큰의 목록 차단,
 * 남의 프로젝트로 대화를 시작하면 응답을 시작하기 전에 404.
 */
@ExtendWith(MockitoExtension.class)
class ChatWidgetAccessTest {

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
    @Mock private ChatProjectService chatProjectService;
    @Mock private com.platform.repository.ConversationSessionRepository sessionRepository;

    @InjectMocks private ChatController chatController;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void widget_token_cannot_use_admin_conversation_api() {
        signIn(widget(null));
        ConversationController controller = new ConversationController(null, null, null);

        assertRejected(controller::rejectWidgetToken, HttpStatus.FORBIDDEN, "widget_forbidden");

        signIn(login());
        assertThatCode(controller::rejectWidgetToken).doesNotThrowAnyException();
    }

    @Test
    void widget_token_without_user_ref_cannot_open_history_or_projects() {
        signIn(widget(null));
        assertRejected(ChatHistoryController::requireOwner, HttpStatus.FORBIDDEN, "widget_user_required");

        signIn(widget("u-77"));
        assertThat(ChatHistoryController.requireOwner()).isEqualTo("u-77");
    }

    @Test
    void starting_a_conversation_in_someone_elses_project_is_404_before_answering() {
        signIn(widget("u-77"));
        String othersProject = "5f0c3c1e-1111-4222-8333-944455556666";
        when(sessionRepository.findBySessionIdIncludingDeleted("s-new")).thenReturn(Optional.empty());
        when(chatProjectService.requireOwned(eq("u-77"), eq(othersProject)))
                .thenThrow(new ChatRejectedException(HttpStatus.NOT_FOUND, "project_not_found"));

        assertRejected(() -> chatController.completions(request("s-new", othersProject)),
                HttpStatus.NOT_FOUND, "project_not_found");
        verifyNoInteractions(orchestrator, rewindService);
    }

    private static ChatController.ChatCompletionRequest request(String sessionId, String chatProjectId) {
        return new ChatController.ChatCompletionRequest(
                "claude-sonnet-4-5", sessionId,
                List.of(new ChatController.MessageDto("user", "질문")),
                true, false, null, null, null, null, null, null, null, null,
                null, null, null, chatProjectId);
    }

    private static UserPrincipal widget(String userRef) {
        Map<String, Object> permissions = userRef == null ? Map.of() : Map.of(ChatOwner.USER_REF_KEY, userRef);
        return new UserPrincipal("widget-sub", null, "tenant-a", ChatOwner.WIDGET_ROLE, permissions);
    }

    private static UserPrincipal login() {
        return new UserPrincipal("user-1", "a@example.com", "tenant-a", "ADMIN", Map.of());
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
