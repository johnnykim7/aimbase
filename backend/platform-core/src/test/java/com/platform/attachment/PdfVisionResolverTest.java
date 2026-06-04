package com.platform.attachment;

import com.platform.rag.MCPRagClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-095: PDF 비전 게이트 — openclaude shouldExtractPages 분기 검증.
 */
@ExtendWith(MockitoExtension.class)
class PdfVisionResolverTest {

    @Mock
    private MCPRagClient ragClient;

    private PdfVisionResolver resolver;

    private static final long INLINE_MAX = 3L * 1024 * 1024;   // 3MB
    private static final long TARGET_MAX = 20L * 1024 * 1024;  // 20MB

    @BeforeEach
    void setUp() {
        resolver = new PdfVisionResolver(ragClient, INLINE_MAX, TARGET_MAX, 20, 100);
    }

    private byte[] bytesOf(long size) {
        return new byte[(int) size];
    }

    @Test
    void smallPdf_supportsPdf_inlinesDocument() {
        byte[] small = bytesOf(1024);  // 1KB ≤ 3MB
        var r = resolver.resolve(small, true, true);

        assertThat(r.mode()).isEqualTo(PdfVisionResolver.Mode.INLINE_PDF);
        assertThat(r.pdfBase64()).isNotBlank();
        assertThat(r.images()).isEmpty();
        // 사이드카 이미지화 호출 안 함
        verify(ragClient, never()).pdfToImages(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void largePdf_supportsPdfAndImage_rendersPageImages() {
        byte[] large = bytesOf(4L * 1024 * 1024);  // 4MB > 3MB
        when(ragClient.pdfToImages(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of(
                        "success", true,
                        "images", List.of(
                                Map.of("page_number", 1, "media_type", "image/jpeg", "data", "AAAA"),
                                Map.of("page_number", 2, "media_type", "image/jpeg", "data", "BBBB")),
                        "page_count", 2,
                        "truncated", false));

        var r = resolver.resolve(large, true, true);

        assertThat(r.mode()).isEqualTo(PdfVisionResolver.Mode.PAGE_IMAGES);
        assertThat(r.images()).hasSize(2);
        assertThat(r.images().get(0).mediaType()).isEqualTo("image/jpeg");
        assertThat(r.images().get(0).base64()).isEqualTo("AAAA");
        verify(ragClient).pdfToImages(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void pdfUnsupported_butImageSupported_rendersPageImages() {
        byte[] small = bytesOf(1024);  // 작아도 supportsPdf=false 면 이미지화
        when(ragClient.pdfToImages(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of(
                        "success", true,
                        "images", List.of(Map.of("page_number", 1, "media_type", "image/jpeg", "data", "X")),
                        "page_count", 1));

        var r = resolver.resolve(small, false, true);

        assertThat(r.mode()).isEqualTo(PdfVisionResolver.Mode.PAGE_IMAGES);
        assertThat(r.images()).hasSize(1);
    }

    @Test
    void neitherPdfNorImageSupported_fallsBackToText() {
        byte[] big = bytesOf(4L * 1024 * 1024);
        var r = resolver.resolve(big, false, false);

        assertThat(r.mode()).isEqualTo(PdfVisionResolver.Mode.TEXT_FALLBACK);
        assertThat(r.note()).contains("does not support");
        // 이미지화 시도조차 안 함
        verify(ragClient, never()).pdfToImages(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void sidecarFailure_fallsBackToText() {
        byte[] large = bytesOf(4L * 1024 * 1024);
        when(ragClient.pdfToImages(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of("success", false, "error", "poppler_missing"));

        var r = resolver.resolve(large, true, true);

        assertThat(r.mode()).isEqualTo(PdfVisionResolver.Mode.TEXT_FALLBACK);
        assertThat(r.note()).contains("page rendering failed");
    }

    @Test
    void sidecarThrows_fallsBackToText() {
        byte[] large = bytesOf(4L * 1024 * 1024);
        when(ragClient.pdfToImages(anyString(), anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("connection reset"));

        var r = resolver.resolve(large, true, true);

        assertThat(r.mode()).isEqualTo(PdfVisionResolver.Mode.TEXT_FALLBACK);
    }

    @Test
    void emptyImages_fallsBackToText() {
        byte[] large = bytesOf(4L * 1024 * 1024);
        when(ragClient.pdfToImages(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of("success", true, "images", List.of(), "page_count", 0));

        var r = resolver.resolve(large, true, true);

        assertThat(r.mode()).isEqualTo(PdfVisionResolver.Mode.TEXT_FALLBACK);
    }

    @Test
    void overTargetRawSize_rendersImagesEvenIfPdfSupported() {
        byte[] huge = bytesOf(21L * 1024 * 1024);  // 21MB > 20MB target
        when(ragClient.pdfToImages(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of(
                        "success", true,
                        "images", List.of(Map.of("page_number", 1, "media_type", "image/jpeg", "data", "Z")),
                        "page_count", 1,
                        "truncated", true));

        var r = resolver.resolve(huge, true, true);

        assertThat(r.mode()).isEqualTo(PdfVisionResolver.Mode.PAGE_IMAGES);
        assertThat(r.note()).contains("truncated");
    }
}
