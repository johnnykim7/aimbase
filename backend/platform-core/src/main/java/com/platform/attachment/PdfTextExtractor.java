package com.platform.attachment;

import com.platform.rag.MCPRagClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * CR-061: PDF 텍스트 추출 폴백.
 * CR-092: 텍스트 박힌 PDF 추출 실패(< 임계) 시 Tesseract OCR 자동 fallback.
 *
 * Anthropic Claude 외 프로바이더(OpenAI/Ollama/Vertex/OpenAICompatible)는 네이티브 PDF 블록을
 * 수용하지 않으므로, ChatController 에서 파일 바이트를 받아 Python 사이드카의 {@code parse_document}
 * 도구로 텍스트를 뽑고 사용자 메시지 앞에 prepend 한다.
 *
 * 1차: parse_document(unstructured) — 텍스트 박힌 PDF 빠름.
 * 2차(CR-092 BIZ-107): 결과 길이 < {@link #ocrFallbackThresholdChars} 면 read_pdf(ocr_enabled=true) 호출.
 *
 * 추가 용도: 업로드 시점의 페이지 수 메타 추출 (AttachmentService 가 선택적으로 호출).
 */
@Component
public class PdfTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    /** 32KB — 첫 N 토큰만 프롬프트에 싣는다. 초과 시 절삭 경고 로그. */
    public static final int MAX_TEXT_LENGTH = 32_000;

    private final MCPRagClient client;

    // CR-092 설정 (application.yml `aimbase.ocr.*`)
    private final boolean ocrEnabled;
    private final int ocrFallbackThresholdChars;
    private final String ocrLanguages;
    private final int ocrMaxPages;

    public PdfTextExtractor(MCPRagClient client,
                            @Value("${aimbase.ocr.enabled:true}") boolean ocrEnabled,
                            @Value("${aimbase.ocr.fallback-threshold-chars:50}") int ocrFallbackThresholdChars,
                            @Value("${aimbase.ocr.languages:kor+eng}") String ocrLanguages,
                            @Value("${aimbase.ocr.max-pages:50}") int ocrMaxPages) {
        this.client = client;
        this.ocrEnabled = ocrEnabled;
        this.ocrFallbackThresholdChars = ocrFallbackThresholdChars;
        this.ocrLanguages = ocrLanguages;
        this.ocrMaxPages = ocrMaxPages;
    }

    /**
     * PDF 바이트 → 텍스트 + 페이지 수.
     * 실패하면 text="", pages=null 을 담은 결과를 반환해 호출부에서 폴백 처리.
     */
    public ExtractResult extract(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return new ExtractResult("", null);
        }
        String base64 = Base64.getEncoder().encodeToString(pdfBytes);

        ExtractResult primary = tryParseDocument(base64);

        // CR-092 BIZ-107: 1차 결과가 임계 미만이면 OCR 시도
        if (ocrEnabled && primary.text().length() < ocrFallbackThresholdChars) {
            log.info("PDF text < {} chars (got {}), falling back to OCR (lang={}, max_pages={})",
                    ocrFallbackThresholdChars, primary.text().length(), ocrLanguages, ocrMaxPages);
            ExtractResult ocrResult = tryOcr(base64);
            if (!ocrResult.isEmpty()) {
                return ocrResult;
            }
            log.info("OCR fallback yielded no text either (pdf bytes={})", pdfBytes.length);
        }

        return primary;
    }

    private ExtractResult tryParseDocument(String base64) {
        try {
            Map<String, Object> result = client.parseDocument(base64, "pdf");

            String text = result.get("text") instanceof String s ? s : "";
            Integer pages = null;
            if (result.get("metadata") instanceof Map<?, ?> meta) {
                Object p = meta.get("pages");
                if (p instanceof Number n) pages = n.intValue();
            }

            return truncate(text, pages);
        } catch (Exception e) {
            log.warn("PDF parse_document failed — returning empty text: {}", e.getMessage());
            return new ExtractResult("", null);
        }
    }

    private ExtractResult tryOcr(String base64) {
        try {
            Map<String, Object> ocrResult = client.readPdf(base64, false, true, ocrLanguages, ocrMaxPages);
            if (!Boolean.TRUE.equals(ocrResult.get("success"))) {
                log.warn("OCR read_pdf failed: {}", ocrResult.get("error"));
                return new ExtractResult("", null);
            }

            String text = joinPages(ocrResult);
            Integer pages = ocrResult.get("page_count") instanceof Number n ? n.intValue() : null;
            return truncate(text, pages);
        } catch (Exception e) {
            log.warn("OCR read_pdf threw: {}", e.getMessage());
            return new ExtractResult("", null);
        }
    }

    private String joinPages(Map<String, Object> ocrResult) {
        Object pagesObj = ocrResult.get("pages");
        if (!(pagesObj instanceof List<?> list)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object item : list) {
            if (item instanceof Map<?, ?> page) {
                Object t = page.get("text");
                if (t instanceof String s && !s.isBlank()) {
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(s);
                }
            }
        }
        return sb.toString();
    }

    private ExtractResult truncate(String text, Integer pages) {
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            log.warn("PDF text truncated from {} to {} chars", text.length(), MAX_TEXT_LENGTH);
            text = text.substring(0, MAX_TEXT_LENGTH) + "\n[... truncated ...]";
        }
        return new ExtractResult(text != null ? text : "", pages);
    }

    public record ExtractResult(String text, Integer pages) {
        public boolean isEmpty() { return text == null || text.isBlank(); }
    }
}
