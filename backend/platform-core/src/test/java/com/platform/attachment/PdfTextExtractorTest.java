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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-061 PdfTextExtractor — parse_document 래퍼 + 절삭 + 실패 graceful.
 * CR-092 — OCR fallback 분기 + 설정 주입.
 */
@ExtendWith(MockitoExtension.class)
class PdfTextExtractorTest {

    @Mock private MCPRagClient client;

    private PdfTextExtractor extractor;
    private PdfTextExtractor extractorOcrDisabled;

    @BeforeEach
    void setUp() {
        // 기본 설정: OCR enabled, 임계 50자, kor+eng, 50p
        extractor = new PdfTextExtractor(client, true, 50, "kor+eng", 50);
        extractorOcrDisabled = new PdfTextExtractor(client, false, 50, "kor+eng", 50);
    }

    private byte[] fakePdf() {
        return new byte[]{0x25, 0x50, 0x44, 0x46};
    }

    @Test
    void extract_returnsEmptyForNullOrEmpty() {
        assertThat(extractor.extract(null).isEmpty()).isTrue();
        assertThat(extractor.extract(new byte[0]).isEmpty()).isTrue();
    }

    @Test
    void extract_aboveThreshold_doesNotTriggerOcr() {
        // 임계 50자 이상 → OCR 호출 안 됨
        String longText = "a".repeat(100);
        when(client.parseDocument(anyString(), anyString()))
                .thenReturn(Map.of("text", longText, "metadata", Map.of("pages", 3)));

        PdfTextExtractor.ExtractResult r = extractor.extract(fakePdf());

        assertThat(r.text()).isEqualTo(longText);
        assertThat(r.pages()).isEqualTo(3);
        verify(client, never()).readPdf(anyString(), anyBoolean(), anyBoolean(), anyString(), anyInt());
    }

    @Test
    void extract_truncatesOver32KB() {
        String huge = "x".repeat(40_000);
        when(client.parseDocument(anyString(), anyString()))
                .thenReturn(Map.of("text", huge, "metadata", Map.of()));

        PdfTextExtractor.ExtractResult r = extractor.extract(fakePdf());

        assertThat(r.text().length()).isLessThanOrEqualTo(PdfTextExtractor.MAX_TEXT_LENGTH + 32);
        assertThat(r.text()).endsWith("[... truncated ...]");
        verify(client, never()).readPdf(anyString(), anyBoolean(), anyBoolean(), anyString(), anyInt());
    }

    @Test
    void extract_parseDocumentException_disabledOcr_returnsEmpty() {
        // OCR disabled → parseDocument 실패 시 그대로 빈 결과
        when(client.parseDocument(anyString(), anyString()))
                .thenThrow(new RuntimeException("MCP down"));

        PdfTextExtractor.ExtractResult r = extractorOcrDisabled.extract(fakePdf());

        assertThat(r.text()).isEmpty();
        assertThat(r.pages()).isNull();
        assertThat(r.isEmpty()).isTrue();
        verify(client, never()).readPdf(anyString(), anyBoolean(), anyBoolean(), anyString(), anyInt());
    }

    @Test
    void extract_handlesMissingMetadata_aboveThreshold() {
        String text = "x".repeat(60);  // 임계 초과로 OCR 회피
        when(client.parseDocument(anyString(), anyString()))
                .thenReturn(Map.of("text", text));

        PdfTextExtractor.ExtractResult r = extractor.extract(fakePdf());

        assertThat(r.text()).isEqualTo(text);
        assertThat(r.pages()).isNull();
    }

    // ─── CR-092 OCR fallback 분기 ──────────────────────────────────

    @Test
    void extract_belowThreshold_triggersOcrFallback() {
        // 1차 짧은 텍스트 → OCR fallback 발동
        when(client.parseDocument(anyString(), anyString()))
                .thenReturn(Map.of("text", "tiny", "metadata", Map.of()));
        when(client.readPdf(anyString(), anyBoolean(), anyBoolean(), anyString(), anyInt()))
                .thenReturn(Map.of(
                        "success", true,
                        "page_count", 2,
                        "pages", List.of(
                                Map.of("text", "OCR page one content", "source", "ocr"),
                                Map.of("text", "OCR page two content", "source", "ocr")
                        )
                ));

        PdfTextExtractor.ExtractResult r = extractor.extract(fakePdf());

        assertThat(r.text()).contains("OCR page one content");
        assertThat(r.text()).contains("OCR page two content");
        assertThat(r.pages()).isEqualTo(2);
        verify(client).readPdf(anyString(), anyBoolean(), anyBoolean(), anyString(), anyInt());
    }

    @Test
    void extract_ocrFailure_returnsPrimaryResult() {
        // OCR 실패 시 1차 결과(짧더라도) 그대로
        when(client.parseDocument(anyString(), anyString()))
                .thenReturn(Map.of("text", "tiny", "metadata", Map.of()));
        when(client.readPdf(anyString(), anyBoolean(), anyBoolean(), anyString(), anyInt()))
                .thenReturn(Map.of("success", false, "error", "tesseract_not_installed"));

        PdfTextExtractor.ExtractResult r = extractor.extract(fakePdf());

        assertThat(r.text()).isEqualTo("tiny");
    }

    @Test
    void extract_ocrThrows_returnsPrimaryResult() {
        when(client.parseDocument(anyString(), anyString()))
                .thenReturn(Map.of("text", "tiny", "metadata", Map.of()));
        when(client.readPdf(anyString(), anyBoolean(), anyBoolean(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("MCP down"));

        PdfTextExtractor.ExtractResult r = extractor.extract(fakePdf());

        assertThat(r.text()).isEqualTo("tiny");
    }

    @Test
    void extract_parseDocumentEmpty_ocrFills() {
        // 1차 빈 결과 + OCR 성공 → OCR 결과 반환
        when(client.parseDocument(anyString(), anyString()))
                .thenReturn(Map.of("text", "", "metadata", Map.of()));
        when(client.readPdf(anyString(), anyBoolean(), anyBoolean(), anyString(), anyInt()))
                .thenReturn(Map.of(
                        "success", true,
                        "page_count", 1,
                        "pages", List.of(Map.of("text", "scanned doc content", "source", "ocr"))
                ));

        PdfTextExtractor.ExtractResult r = extractor.extract(fakePdf());

        assertThat(r.text()).isEqualTo("scanned doc content");
        assertThat(r.pages()).isEqualTo(1);
    }

    @Test
    void extract_ocrDisabled_skipsOcr() {
        // OCR disabled → 임계 미만이어도 OCR 호출 안 함
        when(client.parseDocument(anyString(), anyString()))
                .thenReturn(Map.of("text", "tiny", "metadata", Map.of()));

        PdfTextExtractor.ExtractResult r = extractorOcrDisabled.extract(fakePdf());

        assertThat(r.text()).isEqualTo("tiny");
        verify(client, never()).readPdf(anyString(), anyBoolean(), anyBoolean(), anyString(), anyInt());
    }

    @Test
    void extract_ocrTruncatesOver32KB() {
        // OCR 결과가 32KB 초과 시 절삭
        when(client.parseDocument(anyString(), anyString()))
                .thenReturn(Map.of("text", "", "metadata", Map.of()));
        String huge = "x".repeat(40_000);
        when(client.readPdf(anyString(), anyBoolean(), anyBoolean(), anyString(), anyInt()))
                .thenReturn(Map.of(
                        "success", true,
                        "page_count", 1,
                        "pages", List.of(Map.of("text", huge))
                ));

        PdfTextExtractor.ExtractResult r = extractor.extract(fakePdf());

        assertThat(r.text().length()).isLessThanOrEqualTo(PdfTextExtractor.MAX_TEXT_LENGTH + 32);
        assertThat(r.text()).endsWith("[... truncated ...]");
    }
}
