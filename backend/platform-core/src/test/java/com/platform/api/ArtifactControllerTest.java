package com.platform.api;

import com.platform.artifact.ArtifactPreviewService;
import com.platform.artifact.ArtifactService;
import com.platform.auth.UserPrincipal;
import com.platform.chatdoc.ChatDocumentService;
import com.platform.domain.ConversationSessionEntity;
import com.platform.repository.ConversationSessionRepository;
import com.platform.session.ChatRejectedException;
import com.platform.session.ChatSessionOwnership;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** CR-182 TC-CHT-085(산출물 주인): 위젯 토큰은 세션 주인만 산출물을 연다. */
class ArtifactControllerTest {

    private ArtifactService artifactService;
    private ArtifactPreviewService previewService;
    private ChatDocumentService chatDocuments;
    private ArtifactController controller;

    @BeforeEach
    void setUp() {
        ConversationSessionRepository sessionRepository = mock(ConversationSessionRepository.class);
        ConversationSessionEntity session = new ConversationSessionEntity();
        session.setSessionId("s1");
        session.setUserId("user-a");
        when(sessionRepository.findBySessionId("s1")).thenReturn(Optional.of(session));
        artifactService = mock(ArtifactService.class);
        previewService = mock(ArtifactPreviewService.class);
        chatDocuments = mock(ChatDocumentService.class);
        controller = new ArtifactController(artifactService, previewService, new ChatSessionOwnership(sessionRepository),
                chatDocuments);
    }

    @Test
    @DisplayName("TC-CHT-085 남의 위젯 토큰으로 산출물 목록, 다운로드, 미리보기는 404 artifact_not_found")
    void other_widget_user_gets_not_found() {
        UserPrincipal other = principal("user-b", "WIDGET");

        assertNotFound(() -> controller.list(other, "s1"));
        assertNotFound(() -> controller.download(other, "s1", "보고서.docx", null));
        assertNotFound(() -> controller.download(other, "s1", "보고서.docx", 2));
        assertNotFound(() -> controller.preview(other, "s1", "보고서.docx", null));
        assertNotFound(() -> controller.previewPage(other, "s1", "보고서.docx", 1, 2));
        verifyNoInteractions(artifactService, previewService, chatDocuments);
    }

    @Test
    @DisplayName("TC-CHT-085 세션 주인 위젯 토큰과 소비앱 호출은 지금처럼 목록을 연다")
    void owner_and_non_widget_callers_pass() {
        when(artifactService.listArtifacts("s1")).thenReturn(List.of());

        assertThat(controller.list(principal("user-a", "WIDGET"), "s1")).isNotNull();
        assertThat(controller.list(principal("app-key", "ADMIN"), "s1")).isNotNull();
        assertThat(controller.list(null, "s1")).isNotNull();

        verify(artifactService, times(3)).listArtifacts("s1");
    }

    private static UserPrincipal principal(String id, String role) {
        return new UserPrincipal(id, null, "t1", role, Map.of());
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ChatRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("artifact_not_found");
                });
    }
}
