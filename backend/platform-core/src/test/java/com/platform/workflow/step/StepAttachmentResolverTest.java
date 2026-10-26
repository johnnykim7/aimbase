package com.platform.workflow.step;

import com.platform.attachment.AttachmentService;
import com.platform.attachment.PdfTextExtractor;
import com.platform.attachment.PdfVisionResolver;
import com.platform.domain.ChatAttachmentEntity;
import com.platform.llm.adapter.AdapterCapability;
import com.platform.llm.model.ContentBlock;
import com.platform.workflow.StepContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** CR-159: LLM 단계 attachments 를 그림, 문서 블록으로 바꾸는 규칙. */
class StepAttachmentResolverTest {

    private static final String SESSION = "bp-office-work-7";

    private AttachmentService attachmentService;
    private PdfVisionResolver pdfVisionResolver;
    private PdfTextExtractor pdfTextExtractor;
    private StepAttachmentResolver resolver;

    @BeforeEach
    void setUp() {
        attachmentService = mock(AttachmentService.class);
        pdfVisionResolver = mock(PdfVisionResolver.class);
        pdfTextExtractor = mock(PdfTextExtractor.class);
        resolver = new StepAttachmentResolver(attachmentService, pdfVisionResolver, pdfTextExtractor, 1568);
    }

    private StepContext context(Map<String, Object> input) {
        return new StepContext("run-1", "wf-1", SESSION, input, Map.of());
    }

    private UUID stored(String filename, String mediaType, byte[] bytes) {
        UUID id = UUID.randomUUID();
        ChatAttachmentEntity e = new ChatAttachmentEntity();
        e.setId(id);
        e.setSessionId(SESSION);
        e.setFilename(filename);
        e.setMediaType(mediaType);
        e.setSizeBytes(bytes.length);
        when(attachmentService.loadOwned(id, SESSION)).thenReturn(e);
        when(attachmentService.readBytes(e)).thenReturn(bytes);
        return id;
    }

    private static byte[] image(int w, int h, String format, boolean alpha) throws Exception {
        BufferedImage img = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                img.setRGB(x, y, x < w / 2 ? Color.RED.getRGB() : Color.BLUE.getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private static BufferedImage decode(ContentBlock.Image block) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(block.data())));
    }

    /** SOI 바로 뒤에 Orientation 하나만 든 APP1 Exif(빅엔디언) 를 끼운다. */
    private static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] app1 = {
                (byte) 0xFF, (byte) 0xE1, 0x00, 0x22,
                'E', 'x', 'i', 'f', 0x00, 0x00,
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
                0x00, 0x01,
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, (byte) orientation, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
        };
        byte[] out = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, out, 0, 2);
        System.arraycopy(app1, 0, out, 2, app1.length);
        System.arraycopy(jpeg, 2, out, 2 + app1.length, jpeg.length - 2);
        return out;
    }

    @Test
    @DisplayName("TC-CR159-001: 참조한 입력이 없으면 첨부 없음이고 첨부를 읽지 않는다")
    void absentInputMeansNoAttachments() {
        StepAttachmentResolver.Resolved r = resolver.resolve("extract", "{{input.files}}",
                context(Map.of("text", "본문")), AdapterCapability.IMAGE_AND_PDF);

        assertThat(r.isEmpty()).isTrue();
        assertThat(r.summary()).isEmpty();
        verifyNoInteractions(attachmentService);
    }

    @Test
    @DisplayName("TC-CR159-002: 작은 사진은 원본 그대로 그림 블록, 앞에 파일 이름 한 줄, 실행 세션으로 읽는다")
    void smallImagePassesThrough() throws Exception {
        byte[] png = image(40, 20, "png", false);
        UUID id = stored("명함.png", "image/png", png);

        StepAttachmentResolver.Resolved r = resolver.resolve("extract", "{{input.files}}",
                context(Map.of("files", List.of(id.toString()))), AdapterCapability.IMAGE_AND_PDF);

        assertThat(r.blocks()).hasSize(2);
        assertThat(r.blocks().get(0)).isEqualTo(new ContentBlock.Text("[첨부: 명함.png]"));
        ContentBlock.Image img = (ContentBlock.Image) r.blocks().get(1);
        assertThat(img.mediaType()).isEqualTo("image/png");
        assertThat(img.data()).isEqualTo(Base64.getEncoder().encodeToString(png));
        assertThat(r.summary()).contains("명함.png").contains("image/png").doesNotContain(img.data());
        verify(attachmentService).loadOwned(id, SESSION);
    }

    @Test
    @DisplayName("TC-CR159-003: 긴 변이 상한보다 큰 사진은 비율을 지켜 줄이고 JPEG 로 보낸다")
    void largeImageIsDownscaled() throws Exception {
        UUID id = stored("현장.jpg", "image/jpeg", image(4000, 2000, "jpg", false));

        StepAttachmentResolver.Resolved r = resolver.resolve("extract", List.of(Map.of("attachment_id", id.toString())),
                context(Map.of()), AdapterCapability.IMAGE_AND_PDF);

        ContentBlock.Image img = (ContentBlock.Image) r.blocks().get(1);
        BufferedImage out = decode(img);
        assertThat(img.mediaType()).isEqualTo("image/jpeg");
        assertThat(out.getWidth()).isEqualTo(1568);
        assertThat(out.getHeight()).isEqualTo(784);
        assertThat(r.summary()).contains("줄임");
    }

    @Test
    @DisplayName("TC-CR159-004: 줄일 때 EXIF 회전(6, 시계방향 90도)을 픽셀에 반영해 세로 사진이 눕지 않는다")
    void downscaleAppliesExifOrientation() throws Exception {
        byte[] jpeg = withOrientation(image(4000, 2000, "jpg", false), 6);
        assertThat(StepAttachmentResolver.jpegOrientation(jpeg)).isEqualTo(6);
        UUID id = stored("세로.jpg", "image/jpeg", jpeg);

        StepAttachmentResolver.Resolved r = resolver.resolve("extract", List.of(id.toString()),
                context(Map.of()), AdapterCapability.IMAGE_AND_PDF);

        BufferedImage out = decode((ContentBlock.Image) r.blocks().get(1));
        assertThat(out.getWidth()).isEqualTo(784);
        assertThat(out.getHeight()).isEqualTo(1568);
        // 저장 픽셀의 왼쪽(빨강)이 화면 위로 온다
        assertThat(new Color(out.getRGB(392, 100)).getRed()).isGreaterThan(200);
        assertThat(new Color(out.getRGB(392, 1468)).getBlue()).isGreaterThan(200);
    }

    @Test
    @DisplayName("TC-CR159-005: 회전 표시 1~8 의 픽셀 이동")
    void orientationTransforms() {
        BufferedImage src = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        src.setRGB(0, 0, Color.RED.getRGB());
        src.setRGB(1, 0, Color.BLUE.getRGB());

        assertThat(StepAttachmentResolver.applyOrientation(src, 1)).isSameAs(src);
        BufferedImage o3 = StepAttachmentResolver.applyOrientation(src, 3);
        assertThat(o3.getRGB(0, 0)).isEqualTo(Color.BLUE.getRGB());
        BufferedImage o6 = StepAttachmentResolver.applyOrientation(src, 6);
        assertThat(o6.getWidth()).isEqualTo(1);
        assertThat(o6.getRGB(0, 0)).isEqualTo(Color.RED.getRGB());
        assertThat(o6.getRGB(0, 1)).isEqualTo(Color.BLUE.getRGB());
        BufferedImage o8 = StepAttachmentResolver.applyOrientation(src, 8);
        assertThat(o8.getRGB(0, 0)).isEqualTo(Color.BLUE.getRGB());
        assertThat(o8.getRGB(0, 1)).isEqualTo(Color.RED.getRGB());
    }

    @Test
    @DisplayName("TC-CR159-006: 투명이 있는 큰 PNG 는 PNG 로 줄인다")
    void largeAlphaPngStaysPng() throws Exception {
        UUID id = stored("도면.png", "image/png", image(3136, 100, "png", true));

        StepAttachmentResolver.Resolved r = resolver.resolve("extract", List.of(id.toString()),
                context(Map.of()), AdapterCapability.IMAGE_AND_PDF);

        ContentBlock.Image img = (ContentBlock.Image) r.blocks().get(1);
        assertThat(img.mediaType()).isEqualTo("image/png");
        assertThat(decode(img).getWidth()).isEqualTo(1568);
    }

    @Test
    @DisplayName("TC-CR159-007: PDF 를 연결이 받고 작으면 문서 블록 통째")
    void smallPdfInline() {
        byte[] pdf = "%PDF-1.4 scan".getBytes();
        UUID id = stored("계약서.pdf", "application/pdf", pdf);
        when(pdfVisionResolver.resolve(pdf, true, true)).thenReturn(new PdfVisionResolver.PdfVisionResult(
                PdfVisionResolver.Mode.INLINE_PDF, "UERG", List.of(), null, null));

        StepAttachmentResolver.Resolved r = resolver.resolve("extract", List.of(id.toString()),
                context(Map.of()), AdapterCapability.IMAGE_AND_PDF);

        assertThat(r.blocks().get(1)).isEqualTo(ContentBlock.Document.ofBase64("application/pdf", "UERG", "계약서.pdf"));
        assertThat(r.summary()).contains("PDF 통째");
    }

    @Test
    @DisplayName("TC-CR159-008: 큰 PDF 는 쪽 그림 블록과 잘림 안내")
    void largePdfPageImages() {
        byte[] pdf = "%PDF-1.4 big".getBytes();
        UUID id = stored("견적서.pdf", "application/pdf", pdf);
        when(pdfVisionResolver.resolve(pdf, true, true)).thenReturn(new PdfVisionResolver.PdfVisionResult(
                PdfVisionResolver.Mode.PAGE_IMAGES, null,
                List.of(new PdfVisionResolver.PageImage(1, "image/jpeg", "AAA"),
                        new PdfVisionResolver.PageImage(2, "image/jpeg", "BBB")),
                "Showing first 2 page(s) of 30", 30));

        StepAttachmentResolver.Resolved r = resolver.resolve("extract", List.of(id.toString()),
                context(Map.of()), AdapterCapability.IMAGE_AND_PDF);

        assertThat(r.blocks()).containsExactly(
                new ContentBlock.Text("[첨부: 견적서.pdf]"),
                new ContentBlock.Text("[첨부 PDF: 견적서.pdf, Showing first 2 page(s) of 30]"),
                ContentBlock.Image.ofBase64("image/jpeg", "AAA"),
                ContentBlock.Image.ofBase64("image/jpeg", "BBB"));
        assertThat(r.summary()).contains("쪽 그림 2장");
    }

    @Test
    @DisplayName("TC-CR159-009: 쪽 그림을 못 만들고 글도 비면(스캔 PDF) 빈 원문으로 넘어가지 않고 실패")
    void unreadableScanPdfFails() {
        byte[] pdf = "%PDF-1.4 scan".getBytes();
        UUID id = stored("스캔.pdf", "application/pdf", pdf);
        when(pdfVisionResolver.resolve(any(), anyBoolean(), anyBoolean())).thenReturn(new PdfVisionResolver.PdfVisionResult(
                PdfVisionResolver.Mode.TEXT_FALLBACK, null, List.of(), "page rendering failed: timeout", null));
        when(pdfTextExtractor.extract(pdf)).thenReturn(new PdfTextExtractor.ExtractResult("", 3));

        assertThatThrownBy(() -> resolver.resolve("extract", List.of(id.toString()),
                context(Map.of()), AdapterCapability.IMAGE_AND_PDF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("스캔.pdf")
                .hasMessageContaining("page rendering failed");
    }

    @Test
    @DisplayName("TC-CR159-010: PDF 를 못 받는 연결은 판정기에 그 사실을 전한다")
    void pdfCapabilityPassedToResolver() {
        byte[] pdf = "%PDF-1.4".getBytes();
        UUID id = stored("a.pdf", "application/pdf", pdf);
        when(pdfVisionResolver.resolve(pdf, false, true)).thenReturn(new PdfVisionResolver.PdfVisionResult(
                PdfVisionResolver.Mode.PAGE_IMAGES, null,
                List.of(new PdfVisionResolver.PageImage(1, "image/jpeg", "AAA")), null, 1));

        resolver.resolve("extract", List.of(id.toString()), context(Map.of()), AdapterCapability.IMAGE_ONLY);

        verify(pdfVisionResolver).resolve(eq(pdf), eq(false), eq(true));
    }

    @Test
    @DisplayName("TC-CR159-011: 그림을 못 받는 연결에 사진을 주면 빼고 진행하지 않고 실패")
    void imageOnNoneCapabilityFails() throws Exception {
        UUID id = stored("사진.png", "image/png", image(10, 10, "png", false));

        assertThatThrownBy(() -> resolver.resolve("extract", List.of(id.toString()),
                context(Map.of()), AdapterCapability.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("그림 입력");
    }

    @Test
    @DisplayName("TC-CR159-012: 첨부 번호가 아닌 값은 실패")
    void nonUuidFails() {
        assertThatThrownBy(() -> resolver.resolve("extract", List.of("not-a-uuid"),
                context(Map.of()), AdapterCapability.IMAGE_AND_PDF))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not-a-uuid");
    }

    @Test
    @DisplayName("TC-CR159-013: 상한 0 이면 큰 사진도 줄이지 않는다")
    void zeroMaxEdgeDisablesDownscale() throws Exception {
        resolver = new StepAttachmentResolver(attachmentService, pdfVisionResolver, pdfTextExtractor, 0);
        byte[] jpg = image(2000, 100, "jpg", false);
        UUID id = stored("긴.jpg", "image/jpeg", jpg);

        StepAttachmentResolver.Resolved r = resolver.resolve("extract", List.of(id.toString()),
                context(Map.of()), AdapterCapability.IMAGE_AND_PDF);

        assertThat(((ContentBlock.Image) r.blocks().get(1)).data()).isEqualTo(Base64.getEncoder().encodeToString(jpg));
    }
}
