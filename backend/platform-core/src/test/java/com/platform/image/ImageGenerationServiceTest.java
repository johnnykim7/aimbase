package com.platform.image;

import com.platform.attachment.AttachmentService;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.domain.ChatGeneratedImageEntity;
import com.platform.domain.UsageLogEntity;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import com.platform.repository.ChatGeneratedImageRepository;
import com.platform.repository.UsageLogRepository;
import com.platform.session.CancellationRegistry;
import com.platform.session.ChatRejectedException;
import com.platform.session.SessionStore;
import com.platform.storage.StorageService;
import com.platform.tenant.quota.QuotaCheckResult;
import com.platform.tenant.quota.QuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageGenerationServiceTest {

    private static final String SESSION = "s-1";
    private static final ImageConnection CONN = new ImageConnection("img-1", "gpt-image-1", "medium", "sk-test");

    @Mock private ImageConnectionResolver connectionResolver;
    @Mock private ImageGenerationSettings settings;
    @Mock private OpenAiImageClient client;
    @Mock private ChatGeneratedImageRepository imageRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private StorageService storage;
    @Mock private SessionStore sessionStore;
    @Mock private UsageLogRepository usageLogRepository;
    @Mock private QuotaService quotaService;

    private final CancellationRegistry cancellationRegistry = new CancellationRegistry();
    private ImageGenerationService service;
    private byte[] png;

    @BeforeEach
    void setUp() throws Exception {
        service = new ImageGenerationService(connectionResolver, settings, client, imageRepository,
                attachmentService, storage, sessionStore, cancellationRegistry, usageLogRepository, quotaService);
        lenient().when(connectionResolver.resolve("chat-1")).thenReturn(Optional.of(CONN));
        lenient().when(settings.maxPromptChars()).thenReturn(160);
        lenient().when(settings.maxSourceImages()).thenReturn(2);
        lenient().when(settings.timeoutMs()).thenReturn(5_000L);
        lenient().when(quotaService.checkLLMQuota(any(), anyInt())).thenReturn(QuotaCheckResult.allowed(1000));
        BufferedImage img = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        png = out.toByteArray();
    }

    // ── TC-CHT-101 요청 검사 ──

    @Test
    @DisplayName("TC-CHT-101 1 모르는_action_과_고칠_그림_없는_edit_은_400_image_invalid")
    void invalid_action() {
        assertRejected(() -> service.prepare("chat-1", SESSION, "draw", null, text("고양이")),
                HttpStatus.BAD_REQUEST, "image_invalid");
        assertRejected(() -> service.prepare("chat-1", SESSION, "edit", " ", text("고양이")),
                HttpStatus.BAD_REQUEST, "image_invalid");
    }

    @Test
    @DisplayName("TC-CHT-101 2 그림_연결이_없으면_400_image_not_configured")
    void not_configured() {
        when(connectionResolver.resolve("chat-2")).thenReturn(Optional.empty());

        assertRejected(() -> service.prepare("chat-2", SESSION, "create", null, text("고양이")),
                HttpStatus.BAD_REQUEST, "image_not_configured");
    }

    @Test
    @DisplayName("TC-CHT-101 3 빈_설명은_image_prompt_empty_상한_넘는_글은_image_prompt_too_long")
    void prompt_empty_and_too_long() {
        when(settings.maxPromptChars()).thenReturn(5);

        assertRejected(() -> service.prepare("chat-1", SESSION, "create", null, text("   ")),
                HttpStatus.BAD_REQUEST, "image_prompt_empty");
        assertRejected(() -> service.prepare("chat-1", SESSION, "create", null, text("고양이그림한")),
                HttpStatus.BAD_REQUEST, "image_prompt_too_long");
        assertThat(service.prepare("chat-1", SESSION, "create", null, text("고양이그림")).prompt())
                .isEqualTo("고양이그림");
    }

    @Test
    @DisplayName("TC-CHT-101 4 붙인_그림과_고칠_그림을_합쳐_상한을_넘으면_image_too_many_sources")
    void too_many_sources() {
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        List<ImageGenerationService.RequestMessage> msgs = List.of(new ImageGenerationService.RequestMessage("user",
                List.of(Map.of("type", "text", "text", "합쳐"),
                        Map.of("type", "image", "attachment_id", a1.toString()),
                        Map.of("type", "image", "attachment_id", a2.toString()))));

        assertRejected(() -> service.prepare("chat-1", SESSION, "edit", UUID.randomUUID().toString(), msgs),
                HttpStatus.BAD_REQUEST, "image_too_many_sources");
    }

    @Test
    @DisplayName("TC-CHT-101 5 PDF_를_붙이면_image_source_invalid")
    void document_attached() {
        List<ImageGenerationService.RequestMessage> doc = List.of(new ImageGenerationService.RequestMessage("user",
                List.of(Map.of("type", "text", "text", "그려"),
                        Map.of("type", "document", "attachment_id", UUID.randomUUID().toString()))));
        assertRejected(() -> service.prepare("chat-1", SESSION, "create", null, doc),
                HttpStatus.BAD_REQUEST, "image_source_invalid");

        UUID pdfId = UUID.randomUUID();
        when(attachmentService.loadOwned(pdfId, SESSION)).thenReturn(attachment(pdfId, "application/pdf"));
        List<ImageGenerationService.RequestMessage> pdfAsImage = List.of(new ImageGenerationService.RequestMessage(
                "user", List.of(Map.of("type", "text", "text", "그려"),
                        Map.of("type", "image", "attachment_id", pdfId.toString()))));
        assertRejected(() -> service.prepare("chat-1", SESSION, "create", null, pdfAsImage),
                HttpStatus.BAD_REQUEST, "image_source_invalid");
    }

    @Test
    @DisplayName("TC-CHT-101 6 다른_대화_그림이나_지운_그림이나_모르는_id_는_404_image_not_found")
    void source_image_not_found() {
        UUID other = UUID.randomUUID();
        when(imageRepository.findByIdAndSessionIdAndDeletedAtIsNull(other, SESSION)).thenReturn(Optional.empty());

        assertRejected(() -> service.prepare("chat-1", SESSION, "edit", other.toString(), text("모자")),
                HttpStatus.NOT_FOUND, "image_not_found");
        assertRejected(() -> service.prepare("chat-1", SESSION, "edit", "not-a-uuid", text("모자")),
                HttpStatus.NOT_FOUND, "image_not_found");
        verifyNoInteractions(client);
    }

    // ── TC-CHT-102, 104 저장과 대화 기록 ──

    @Test
    @DisplayName("TC-CHT-104 1 성공하면_파일과_그림_줄과_질문_답_한_묶음을_남기고_사용량을_그림_모델로_적는다")
    void success_saves_file_row_turn_and_usage() {
        String prompt = "첫줄\n[둘째]\\" + "가".repeat(150);
        stubProvider(new OpenAiImageClient.Result(png, 12, 345));
        when(storage.save(any(), eq("generated-images"), anyString(), any(InputStream.class)))
                .thenAnswer(inv -> "tenant/generated-images/" + inv.getArgument(2));
        when(sessionStore.appendTurn(eq(SESSION), anyList(), any(), eq("gpt-image-1"), any())).thenReturn(7);

        ImageGenerationService.Prepared p = service.prepare("chat-1", SESSION, "create", null, text(prompt));
        ImageGenerationService.Outcome outcome = service.generate(p);

        assertThat(outcome.aborted()).isFalse();
        assertThat(outcome.turnSeq()).isEqualTo(7);
        assertThat(outcome.model()).isEqualTo("gpt-image-1");

        ArgumentCaptor<ChatGeneratedImageEntity> row = ArgumentCaptor.forClass(ChatGeneratedImageEntity.class);
        verify(imageRepository).save(row.capture());
        ChatGeneratedImageEntity saved = row.getValue();
        assertThat(saved.getSessionId()).isEqualTo(SESSION);
        assertThat(saved.getTurnId()).isEqualTo(outcome.turnId());
        assertThat(saved.getMediaType()).isEqualTo("image/png");
        assertThat(saved.getWidth()).isEqualTo(3);
        assertThat(saved.getHeight()).isEqualTo(2);
        assertThat(saved.getSizeBytes()).isEqualTo(png.length);
        assertThat(saved.getStoragePath()).isEqualTo("tenant/generated-images/" + saved.getId() + ".png");
        assertThat(saved.getSources()).isEmpty();

        String alt = ("첫줄  둘째  " + "가".repeat(150)).substring(0, 100);
        String md = "![" + alt + "](aimbase-image:" + saved.getId() + ")";
        assertThat(outcome.markdown()).isEqualTo(md);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UnifiedMessage>> turn = ArgumentCaptor.forClass(List.class);
        verify(sessionStore).appendTurn(eq(SESSION), turn.capture(), eq(outcome.turnId()), eq("gpt-image-1"), any());
        assertThat(turn.getValue()).hasSize(2);
        assertThat(turn.getValue().get(0).role()).isEqualTo(UnifiedMessage.Role.USER);
        assertThat(turn.getValue().get(1).role()).isEqualTo(UnifiedMessage.Role.ASSISTANT);
        assertThat(((ContentBlock.Text) turn.getValue().get(1).content().get(0)).text()).isEqualTo(md);

        ArgumentCaptor<UsageLogEntity> usage = ArgumentCaptor.forClass(UsageLogEntity.class);
        verify(usageLogRepository).save(usage.capture());
        assertThat(usage.getValue().getModel()).isEqualTo("gpt-image-1");
        assertThat(usage.getValue().getInputTokens()).isEqualTo(12);
        assertThat(usage.getValue().getOutputTokens()).isEqualTo(345);
        assertThat(cancellationRegistry.isActive(SESSION)).isFalse();
    }

    @Test
    @DisplayName("TC-CHT-102 3 고치기는_고칠_그림을_앞에_붙인_그림을_뒤에_바탕으로_보내고_출처를_적는다")
    void edit_sends_base_then_attachment() {
        UUID baseId = UUID.randomUUID();
        UUID attId = UUID.randomUUID();
        ChatGeneratedImageEntity base = new ChatGeneratedImageEntity();
        base.setId(baseId);
        base.setSessionId(SESSION);
        base.setMediaType("image/png");
        base.setStoragePath("p/base.png");
        when(imageRepository.findByIdAndSessionIdAndDeletedAtIsNull(baseId, SESSION)).thenReturn(Optional.of(base));
        when(storage.load("p/base.png")).thenReturn(new ByteArrayInputStream(png));
        ChatAttachmentEntity att = attachment(attId, "image/jpeg");
        when(attachmentService.loadOwned(attId, SESSION)).thenReturn(att);
        when(attachmentService.readBytes(att)).thenReturn(new byte[]{1, 2, 3});
        List<ImageGenerationService.RequestMessage> msgs = List.of(new ImageGenerationService.RequestMessage("user",
                List.of(Map.of("type", "text", "text", "이 모자를 씌워"),
                        Map.of("type", "image", "attachment_id", attId.toString()))));

        ImageGenerationService.Prepared p = service.prepare("chat-1", SESSION, "edit", baseId.toString(), msgs);

        assertThat(p.sources()).extracting(OpenAiImageClient.SourceImage::mediaType)
                .containsExactly("image/png", "image/jpeg");
        assertThat(p.sourceRefs()).containsExactly(
                Map.of("type", "generated", "id", baseId.toString()),
                Map.of("type", "attachment", "id", attId.toString()));
        assertThat(p.requestMessages().get(0).content()).hasSize(2);
    }

    // ── TC-CHT-103 공급처 실패 ──

    @Test
    @DisplayName("TC-CHT-103 5 공급처_실패는_코드를_올리고_대화_기록과_그림_줄을_남기지_않는다")
    void provider_failure_leaves_nothing() {
        HttpResponse<byte[]> resp = response();
        when(client.request(any(), anyString(), anyList(), any())).thenReturn(CompletableFuture.completedFuture(resp));
        when(client.toResult(resp)).thenThrow(new ImageProviderException(ImageProviderException.QUOTA, "insufficient_quota"));

        ImageGenerationService.Prepared p = service.prepare("chat-1", SESSION, "create", null, text("고양이"));

        assertThatThrownBy(() -> service.generate(p))
                .isInstanceOf(ImageProviderException.class)
                .extracting("code").isEqualTo("image_provider_quota");
        verify(sessionStore, never()).appendTurn(anyString(), anyList(), any(), any(), any());
        verify(imageRepository, never()).save(any());
        verifyNoInteractions(storage);
        assertThat(cancellationRegistry.isActive(SESSION)).isFalse();
    }

    @Test
    @DisplayName("TC-CHT-103 6 시간_상한을_넘으면_호출을_끊고_image_timeout")
    void timeout_cancels_call() {
        when(settings.timeoutMs()).thenReturn(100L);
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        when(client.request(any(), anyString(), anyList(), any())).thenReturn(pending);

        ImageGenerationService.Prepared p = service.prepare("chat-1", SESSION, "create", null, text("고양이"));

        assertThatThrownBy(() -> service.generate(p))
                .isInstanceOf(ImageProviderException.class)
                .extracting("code").isEqualTo("image_timeout");
        assertThat(pending.isCancelled()).isTrue();
        verify(sessionStore, never()).appendTurn(anyString(), anyList(), any(), any(), any());
    }

    // ── TC-CHT-105 응답 중지 ──

    @Test
    @DisplayName("TC-CHT-105 1 중지하면_호출을_끊고_질문과_중단됨_답만_남긴다")
    void abort_saves_question_and_aborted_answer() throws Exception {
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        when(client.request(any(), anyString(), anyList(), any())).thenReturn(pending);
        when(sessionStore.appendTurn(eq(SESSION), anyList(), any(), eq("gpt-image-1"), any())).thenReturn(9);
        ImageGenerationService.Prepared p = service.prepare("chat-1", SESSION, "create", null, text("고양이"));

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<ImageGenerationService.Outcome> running = exec.submit(() -> service.generate(p));
            long until = System.currentTimeMillis() + 5_000;
            while (!cancellationRegistry.isActive(SESSION) && System.currentTimeMillis() < until) {
                Thread.onSpinWait();
            }
            assertThat(cancellationRegistry.cancel(SESSION)).isTrue();
            ImageGenerationService.Outcome outcome = running.get(5, TimeUnit.SECONDS);

            assertThat(outcome.aborted()).isTrue();
            assertThat(outcome.turnSeq()).isEqualTo(9);
            assertThat(outcome.image()).isNull();
            assertThat(pending.isCancelled()).isTrue();
        } finally {
            exec.shutdownNow();
        }
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UnifiedMessage>> turn = ArgumentCaptor.forClass(List.class);
        verify(sessionStore).appendTurn(eq(SESSION), turn.capture(), any(), eq("gpt-image-1"), any());
        assertThat(((ContentBlock.Text) turn.getValue().get(1).content().get(0)).text()).isEqualTo("[중단됨]");
        verify(imageRepository, never()).save(any());
        verifyNoInteractions(storage);
    }

    // ── TC-CHT-107 그림 받기(대화와 지운 표시 검사) ──

    @Test
    @DisplayName("TC-CHT-107 6 그_대화의_지우지_않은_그림이면_바이트를_준다")
    void load_for_session_returns_bytes() {
        UUID id = UUID.randomUUID();
        ChatGeneratedImageEntity row = new ChatGeneratedImageEntity();
        row.setId(id);
        row.setStoragePath("p/x.png");
        row.setMediaType("image/png");
        when(imageRepository.findByIdAndSessionIdAndDeletedAtIsNull(id, SESSION)).thenReturn(Optional.of(row));
        when(storage.load("p/x.png")).thenReturn(new ByteArrayInputStream(png));

        assertThat(service.loadForSession(id.toString(), SESSION).bytes()).isEqualTo(png);
        assertRejected(() -> service.loadForSession(id.toString(), null), HttpStatus.NOT_FOUND, "image_not_found");
    }

    private void stubProvider(OpenAiImageClient.Result result) {
        HttpResponse<byte[]> resp = response();
        when(client.request(any(), anyString(), anyList(), any())).thenReturn(CompletableFuture.completedFuture(resp));
        when(client.toResult(resp)).thenReturn(result);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response() {
        return mock(HttpResponse.class);
    }

    private static ChatAttachmentEntity attachment(UUID id, String mediaType) {
        ChatAttachmentEntity e = new ChatAttachmentEntity();
        e.setId(id);
        e.setSessionId(SESSION);
        e.setMediaType(mediaType);
        return e;
    }

    private static List<ImageGenerationService.RequestMessage> text(String t) {
        return List.of(new ImageGenerationService.RequestMessage("user", t));
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                       HttpStatus status, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(ChatRejectedException.class)
                .hasMessage(code)
                .extracting("status").isEqualTo(status);
    }
}
