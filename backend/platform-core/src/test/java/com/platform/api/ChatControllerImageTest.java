package com.platform.api;

import com.platform.domain.ChatGeneratedImageEntity;
import com.platform.image.ImageConnection;
import com.platform.image.ImageGenerationService;
import com.platform.image.ImageGenerationSettings;
import com.platform.image.ImageProviderException;
import com.platform.orchestrator.OrchestratorEngine;
import com.platform.session.ChatRejectedException;
import com.platform.session.ConversationRewindService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/** CR-184: 대화 요청의 그림 갈래. 모델을 부르지 않고 그림 이벤트 순서와 실패 코드를 지킨다. */
@ExtendWith(MockitoExtension.class)
class ChatControllerImageTest {

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

    @InjectMocks private ChatController controller;

    private static final ImageConnection CONN = new ImageConnection("img-1", "gpt-image-1", "medium", "sk");
    private final ImageGenerationService.Prepared prepared =
            new ImageGenerationService.Prepared("s-1", CONN, "고양이", List.of(), List.of(), List.of());
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        lenient().when(imageSettings.timeoutMs()).thenReturn(5_000L);
        lenient().when(imageGeneration.prepare(eq("chat-1"), eq("s-1"), anyString(), any(), anyList()))
                .thenReturn(prepared);
    }

    @Test
    @DisplayName("TC-CHT-109 1 그림_칸이_있으면_모델과_깊게_생각하기를_무시하고_모델을_부르지_않는다")
    void image_request_skips_model() {
        when(imageGeneration.generate(prepared)).thenReturn(success());

        Object res = controller.completions(chatRequest(false, "claude-opus-5-5", "on", null));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ((ApiResponse<?>) res).data();
        assertThat(body.get("model")).isEqualTo("gpt-image-1");
        assertThat((List<?>) body.get("images")).hasSize(1);
        assertThat(body.get("turn_seq")).isEqualTo(7);
        verifyNoInteractions(orchestrator, connectionAdapterFactory, widgetSettings);
    }

    @Test
    @DisplayName("TC-CHT-109 2 그림_검사에_걸리면_되감지_않고_그_코드로_거절한다")
    void rejected_before_rewind() {
        when(imageGeneration.prepare(eq("chat-1"), eq("s-1"), anyString(), any(), anyList()))
                .thenThrow(new ChatRejectedException(HttpStatus.BAD_REQUEST, "image_prompt_too_long"));

        assertThatThrownBy(() -> controller.completions(chatRequest(false, null, null, 3)))
                .isInstanceOf(ChatRejectedException.class)
                .hasMessage("image_prompt_too_long");
        verify(rewindService, never()).rewind(anyString(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("TC-CHT-103 7 한_번에_받기의_공급처_실패는_502_와_그_코드")
    void provider_failure_non_stream_is_502() {
        when(imageGeneration.generate(prepared))
                .thenThrow(new ImageProviderException(ImageProviderException.QUOTA, null));

        assertThatThrownBy(() -> controller.completions(chatRequest(false, null, null, null)))
                .isInstanceOf(ChatRejectedException.class)
                .hasMessage("image_provider_quota")
                .extracting("status").isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    @DisplayName("TC-CHT-104 2 스트림은_image_status_delta_done_순서이고_done_에_답_번호와_그림을_싣는다")
    void stream_event_order() throws Exception {
        ImageGenerationService.Outcome outcome = success();
        when(imageGeneration.generate(prepared)).thenReturn(outcome);

        String body = stream();

        int status = body.indexOf("event:image_status");
        int delta = body.indexOf("event:delta");
        int done = body.indexOf("event:done");
        assertThat(status).isGreaterThanOrEqualTo(0);
        assertThat(delta).isGreaterThan(status);
        assertThat(done).isGreaterThan(delta);
        assertThat(body).contains("\"state\":\"generating\"")
                .contains("aimbase-image:" + outcome.image().getId())
                .contains("\"turn_seq\":7")
                .contains("\"model\":\"gpt-image-1\"")
                .contains("\"media_type\":\"image/png\"");
    }

    @Test
    @DisplayName("TC-CHT-103 8 스트림의_공급처_실패는_error_코드를_보내고_닫는다")
    void stream_provider_failure_sends_error() throws Exception {
        when(imageGeneration.generate(prepared))
                .thenThrow(new ImageProviderException(ImageProviderException.REJECTED, "moderation_blocked"));

        String body = stream();

        assertThat(body).contains("event:error").contains("\"code\":\"image_rejected\"")
                .doesNotContain("event:delta").doesNotContain("event:done");
    }

    @Test
    @DisplayName("TC-CHT-105 2 스트림_중지는_delta_없이_done_에_turn_seq_를_준다")
    void stream_abort_sends_done_without_delta() throws Exception {
        when(imageGeneration.generate(prepared)).thenReturn(
                new ImageGenerationService.Outcome(true, UUID.randomUUID(), 9, "gpt-image-1", null, null));

        String body = stream();

        assertThat(body).doesNotContain("event:delta").contains("event:done").contains("\"turn_seq\":9")
                .contains("\"images\":[]");
    }

    private String stream() throws Exception {
        String json = "{\"session_id\":\"s-1\",\"connection_id\":\"chat-1\",\"stream\":true,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"고양이\"}],\"image\":{\"action\":\"create\"}}";
        MvcResult r = mvc.perform(post("/api/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON).content(json.getBytes(StandardCharsets.UTF_8)))
                .andExpect(request().asyncStarted())
                .andReturn();
        r.getAsyncResult(5_000);
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static ImageGenerationService.Outcome success() {
        ChatGeneratedImageEntity img = new ChatGeneratedImageEntity();
        img.setId(UUID.randomUUID());
        img.setMediaType("image/png");
        img.setWidth(1024);
        img.setHeight(1024);
        String md = "![고양이](aimbase-image:" + img.getId() + ")";
        return new ImageGenerationService.Outcome(false, UUID.randomUUID(), 7, "gpt-image-1", md, img);
    }

    private static ChatController.ChatCompletionRequest chatRequest(boolean stream, String model, String thinking,
                                                                Integer rewindFromSeq) {
        return new ChatController.ChatCompletionRequest(
                model, "s-1",
                List.of(new ChatController.MessageDto("user", "고양이")),
                stream, false, "chat-1", null, null, null, null, null, null, null,
                rewindFromSeq, thinking, new ChatController.ImageDto("create", null));
    }
}
