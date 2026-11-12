package com.platform.api;

import com.platform.session.ChatRejectedException;
import com.platform.session.ConversationRewindService;
import com.platform.session.RewindRejectedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CR-175: 되감기 거절 사유가 위젯이 읽는 HTTP 코드와 오류 글자로 바뀌는지 본다.
 * 스트림을 열기 전에 거절해야 위젯이 HTTP 코드로 안내를 고를 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerRewindTest {

    @Mock private com.platform.orchestrator.OrchestratorEngine orchestrator;
    @Mock private com.platform.config.WorkspaceProperties workspaceProperties;
    @Mock private com.platform.session.CancellationRegistry cancellationRegistry;
    @Mock private com.platform.attachment.AttachmentService attachmentService;
    @Mock private com.platform.attachment.PdfTextExtractor pdfTextExtractor;
    @Mock private com.platform.attachment.PdfVisionResolver pdfVisionResolver;
    @Mock private com.platform.llm.LLMAdapterRegistry adapterRegistry;
    @Mock private com.platform.artifact.ArtifactService artifactService;
    @Mock private com.platform.llm.ConnectionAdapterFactory connectionAdapterFactory;
    @Mock private ConversationRewindService rewindService;
    @Mock private com.platform.session.ChatWidgetSettingsService widgetSettings;

    @InjectMocks private ChatController controller;

    @Test
    @DisplayName("IT-09-004 2 답이_도는_세션의_되감기는_409_session_busy")
    void busy_session_is_409() {
        rejectWith(RewindRejectedException.Reason.SESSION_BUSY);

        assertRejected(HttpStatus.CONFLICT, "session_busy");
    }

    @Test
    @DisplayName("IT-09-005 2 묶음_첫_순번이_아니면_400_rewind_invalid_not_turn_start")
    void seq_inside_a_turn_is_400() {
        rejectWith(RewindRejectedException.Reason.NOT_TURN_START);

        assertRejected(HttpStatus.BAD_REQUEST, "rewind_invalid:not_turn_start");
    }

    @Test
    @DisplayName("IT-09-005 3 압축_경계_앞_순번은_409_rewind_before_compaction")
    void turn_before_compaction_is_409() {
        rejectWith(RewindRejectedException.Reason.BEFORE_COMPACTION);

        assertRejected(HttpStatus.CONFLICT, "rewind_before_compaction");
    }

    private void rejectWith(RewindRejectedException.Reason reason) {
        doThrow(new RewindRejectedException(reason))
                .when(rewindService).rewind(anyString(), anyInt(), any(), any());
    }

    private void assertRejected(HttpStatus status, String code) {
        assertThatThrownBy(() -> controller.completions(request()))
                .isInstanceOf(ChatRejectedException.class)
                .hasMessage(code)
                .extracting("status").isEqualTo(status);
        verifyNoInteractions(orchestrator);
    }

    private static ChatController.ChatCompletionRequest request() {
        return new ChatController.ChatCompletionRequest(
                "claude-sonnet-4-5", "s-1",
                List.of(new ChatController.MessageDto("user", "고친 질문")),
                true, false, null, null, null, null, null, null, null, null,
                2, null, null, null);
    }
}
