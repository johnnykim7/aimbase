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
import static org.mockito.ArgumentMatchers.eq;
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
        resolver = new PdfVisionResolver(ragClient, INLINE_MAX, TARGET_MAX, 20, 100, 10);
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
                        "total_pages", 30,
                        "truncated", true));

        var r = resolver.resolve(huge, true, true);

        assertThat(r.mode()).isEqualTo(PdfVisionResolver.Mode.PAGE_IMAGES);
        assertThat(r.note()).contains("Showing first");
    }

    // ── CR-095 후속: 페이지 분할 가드 (allowSplitGuard=true, 도구 경로) ──

    @Test
    void manyPages_withGuard_returnsNeedsSplit() {
        byte[] large = bytesOf(13L * 1024 * 1024);  // 13MB
        when(ragClient.pdfPageCount(anyString()))
                .thenReturn(Map.of("success", true, "page_count", 29));  // > 10 임계

        var r = resolver.resolve(large, true, true, null, true);

        assertThat(r.mode()).isEqualTo(PdfVisionResolver.Mode.NEEDS_SPLIT);
        assertThat(r.totalPages()).isEqualTo(29);
        assertThat(r.note()).contains("pages parameter");
        // 가드에 걸리면 이미지화(통째 렌더) 안 함 — turn timeout 방지
        verify(ragClient, never()).pdfToImages(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void manyPages_withPagesSpecified_rendersThatRange() {
        byte[] large = bytesOf(13L * 1024 * 1024);
        when(ragClient.pdfToImages(anyString(), eq("1-10"), anyInt(), anyInt()))
                .thenReturn(Map.of(
                        "success", true,
                        "images", List.of(
                                Map.of("page_number", 1, "media_type", "image/jpeg", "data", "A"),
                                Map.of("page_number", 2, "media_type", "image/jpeg", "data", "B")),
                        "page_count", 2,
                        "total_pages", 29));

        // pages 지정 → 가드 우회, 그 범위만 이미지화. 페이지 수 카운트 호출 안 함.
        var r = resolver.resolve(large, true, true, "1-10", true);

        assertThat(r.mode()).isEqualTo(PdfVisionResolver.Mode.PAGE_IMAGES);
        assertThat(r.images()).hasSize(2);
        verify(ragClient, never()).pdfPageCount(anyString());
    }

    @Test
    void fewPages_withGuard_proceedsNormally() {
        byte[] large = bytesOf(13L * 1024 * 1024);
        when(ragClient.pdfPageCount(anyString()))
                .thenReturn(Map.of("success", true, "page_count", 5));  // ≤ 10
        when(ragClient.pdfToImages(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of(
                        "success", true,
                        "images", List.of(Map.of("page_number", 1, "media_type", "image/jpeg", "data", "X")),
                        "page_count", 1,
                        "total_pages", 5));

        var r = resolver.resolve(large, true, true, null, true);

        assertThat(r.mode()).isEqualTo(PdfVisionResolver.Mode.PAGE_IMAGES);
    }

    @Test
    void pageCountFails_withGuard_skipsGuardAndProceeds() {
        byte[] large = bytesOf(13L * 1024 * 1024);
        when(ragClient.pdfPageCount(anyString()))
                .thenReturn(Map.of("success", false, "error", "page_count_failed"));
        when(ragClient.pdfToImages(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of(
                        "success", true,
                        "images", List.of(Map.of("page_number", 1, "media_type", "image/jpeg", "data", "X")),
                        "page_count", 1));

        // 페이지 수 모르면 가드 건너뛰고 진행 (이미지화) — null 가드는 막지 않음
        var r = resolver.resolve(large, true, true, null, true);

        assertThat(r.mode()).isEqualTo(PdfVisionResolver.Mode.PAGE_IMAGES);
    }

    @Test
    void manyPages_withoutGuard_doesNotSplit() {
        // 첨부 경로(allowSplitGuard=false) — 가드 미적용, 첫 페이지들 이미지화
        byte[] large = bytesOf(13L * 1024 * 1024);
        when(ragClient.pdfToImages(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of(
                        "success", true,
                        "images", List.of(Map.of("page_number", 1, "media_type", "image/jpeg", "data", "X")),
                        "page_count", 1,
                        "total_pages", 29,
                        "truncated", true));

        var r = resolver.resolve(large, true, true, null, false);

        assertThat(r.mode()).isEqualTo(PdfVisionResolver.Mode.PAGE_IMAGES);
        verify(ragClient, never()).pdfPageCount(anyString());
    }
}
