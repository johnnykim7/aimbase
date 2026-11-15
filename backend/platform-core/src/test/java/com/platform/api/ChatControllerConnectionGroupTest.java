package com.platform.api;

import com.platform.image.ImageGenerationService;
import com.platform.image.ImageGenerationSettings;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.session.ChatRejectedException;
import com.platform.session.ConversationRewindService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** CR-194 TC-CHT-152: 계정 묶음으로 묻는 대화 요청의 모델 검사와 능력 판단(BIZ-281). */
@ExtendWith(MockitoExtension.class)
class ChatControllerConnectionGroupTest {

    @Mock private OrchestratorEngine orchestrator;
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
    @Mock private ImageGenerationService imageGeneration;
    @Mock private ImageGenerationSettings imageSettings;
    @Mock private com.platform.session.ChatSessionOwnership sessionAccess;
    @Mock private com.platform.session.SharedConversationService sharedConversations;
    @InjectMocks private ChatController controller;

    @Test
    @DisplayName("TC-CHT-152 3 묶음은_구성원_모두가_받는_모델만_받고_같이_준_연결은_보지_않는다")
    void model_must_be_allowed_by_every_member() {
        when(widgetSettings.groupMembers("g1")).thenReturn(List.of("c1", "c2"));
        when(connectionAdapterFactory.isModelAllowed("c1", "claude-opus-5-5")).thenReturn(true);
        when(connectionAdapterFactory.isModelAllowed("c2", "claude-opus-5-5")).thenReturn(false);

        assertThatThrownBy(() -> controller.completions(request("claude-opus-5-5", "solo", "g1", null)))
                .isInstanceOf(ChatRejectedException.class)
                .hasMessage("model_not_allowed");
        verify(connectionAdapterFactory, never()).isModelAllowed(eq("solo"), any());
        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("TC-CHT-152 3 묶음의_깊게_생각하기_검사는_첫_연결로_본다")
    void thinking_check_uses_first_member() {
        when(widgetSettings.groupMembers("g1")).thenReturn(List.of("c1", "c2"));
        when(connectionAdapterFactory.isModelAllowed(anyString(), any())).thenReturn(true);
        when(widgetSettings.thinkingSupported("c1", null)).thenReturn(false);

        assertThatThrownBy(() -> controller.completions(request(null, null, "g1", "on")))
                .isInstanceOf(ChatRejectedException.class)
                .hasMessage("thinking_not_supported");
        verifyNoInteractions(orchestrator);
    }

    private static ChatController.ChatCompletionRequest request(String model, String connectionId,
                                                               String connectionGroupId, String thinking) {
        return new ChatController.ChatCompletionRequest(
                model, "s-1", List.of(new ChatController.MessageDto("user", "안녕")),
                false, false, connectionId, connectionGroupId, null, null, null, null, null, null,
                null, thinking);
    }
}
