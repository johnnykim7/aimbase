package com.platform.attachment;

import com.platform.rag.MCPRagClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;

/**
 * CR-061: PDF 텍스트 추출 폴백.
 *
 * Anthropic Claude 외 프로바이더(OpenAI/Ollama/Vertex/OpenAICompatible)는 네이티브 PDF 블록을
 * 수용하지 않으므로, ChatController 에서 파일 바이트를 받아 Python 사이드카의 {@code parse_document}
 * 도구로 텍스트를 뽑고 사용자 메시지 앞에 prepend 한다.
 *
 * 추가 용도: 업로드 시점의 페이지 수 메타 추출 (AttachmentService 가 선택적으로 호출).
 */
@Component
public class PdfTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    /** 32KB — 첫 N 토큰만 프롬프트에 싣는다. 초과 시 절삭 경고 로그. */
    public static final int MAX_TEXT_LENGTH = 32_000;

    private final MCPRagClient client;

    public PdfTextExtractor(MCPRagClient client) {
        this.client = client;
    }

    /**
     * PDF 바이트 → 텍스트 + 페이지 수.
     * 실패하면 text="", pages=null 을 담은 결과를 반환해 호출부에서 폴백 처리.
     */
    public ExtractResult extract(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return new ExtractResult("", null);
        }
        try {
            String base64 = Base64.getEncoder().encodeToString(pdfBytes);
            Map<String, Object> result = client.parseDocument(base64, "pdf");

            String text = result.get("text") instanceof String s ? s : "";
            Integer pages = null;
            if (result.get("metadata") instanceof Map<?, ?> meta) {
                Object p = meta.get("pages");
                if (p instanceof Number n) pages = n.intValue();
            }

            if (text.length() > MAX_TEXT_LENGTH) {
                log.warn("PDF text truncated from {} to {} chars", text.length(), MAX_TEXT_LENGTH);
                text = text.substring(0, MAX_TEXT_LENGTH) + "\n[... truncated ...]";
            }
            return new ExtractResult(text, pages);
        } catch (Exception e) {
            log.warn("PDF extraction failed — returning empty text (pdf bytes={}): {}",
                    pdfBytes.length, e.getMessage());
            return new ExtractResult("", null);
        }
    }

    public record ExtractResult(String text, Integer pages) {
        public boolean isEmpty() { return text == null || text.isBlank(); }
    }
}
