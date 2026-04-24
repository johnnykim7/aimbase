package com.platform.attachment;

import com.platform.rag.MCPRagClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * CR-061 PdfTextExtractor — parse_document 래퍼 + 절삭 + 실패 graceful.
 */
@ExtendWith(MockitoExtension.class)
class PdfTextExtractorTest {

    @Mock private MCPRagClient client;

    private PdfTextExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new PdfTextExtractor(client);
    }

    @Test
    void extract_returnsEmptyForNullOrEmpty() {
        assertThat(extractor.extract(null).isEmpty()).isTrue();
        assertThat(extractor.extract(new byte[0]).isEmpty()).isTrue();
    }

    @Test
    void extract_mapsTextAndPages() {
        when(client.parseDocument(anyString(), anyString()))
                .thenReturn(Map.of(
                        "text", "hello pdf",
                        "metadata", Map.of("pages", 3)
                ));

        PdfTextExtractor.ExtractResult r = extractor.extract(new byte[]{0x25, 0x50, 0x44, 0x46});

        assertThat(r.text()).isEqualTo("hello pdf");
        assertThat(r.pages()).isEqualTo(3);
    }

    @Test
    void extract_truncatesOver32KB() {
        String huge = "x".repeat(40_000);
        when(client.parseDocument(anyString(), anyString()))
                .thenReturn(Map.of("text", huge, "metadata", Map.of()));

        PdfTextExtractor.ExtractResult r = extractor.extract(new byte[]{0x25, 0x50, 0x44, 0x46});

        assertThat(r.text().length()).isLessThanOrEqualTo(PdfTextExtractor.MAX_TEXT_LENGTH + 32);
        assertThat(r.text()).endsWith("[... truncated ...]");
    }

    @Test
    void extract_fallsBackToEmptyOnException() {
        when(client.parseDocument(anyString(), anyString()))
                .thenThrow(new RuntimeException("MCP down"));

        PdfTextExtractor.ExtractResult r = extractor.extract(new byte[]{0x25, 0x50, 0x44, 0x46});

        assertThat(r.text()).isEmpty();
        assertThat(r.pages()).isNull();
        assertThat(r.isEmpty()).isTrue();
    }

    @Test
    void extract_handlesMissingMetadata() {
        when(client.parseDocument(anyString(), anyString()))
                .thenReturn(Map.of("text", "x"));

        PdfTextExtractor.ExtractResult r = extractor.extract(new byte[]{0x25, 0x50, 0x44, 0x46});

        assertThat(r.text()).isEqualTo("x");
        assertThat(r.pages()).isNull();
    }
}
