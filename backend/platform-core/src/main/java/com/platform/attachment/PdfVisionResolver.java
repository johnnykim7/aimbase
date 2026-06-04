package com.platform.attachment;

import com.platform.rag.MCPRagClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * CR-095: PDF 비전 파싱 게이트 — openclaude FileReadTool 1:1 포팅.
 *
 * PDF 를 사이드카 텍스트 추출(unstructured/OCR)이 아니라 LLM 모델 비전으로 파싱하기 위해,
 * 크기/모델지원 게이트로 처리 모드를 결정한다:
 *
 * <ol>
 *   <li>≤ inlineMaxBytes & supportsPdf  → INLINE_PDF: base64 document block 통째 (모델이 PDF 직접 봄)</li>
 *   <li>그 외 (>3MB or PDF 미지원)        → PAGE_IMAGES: poppler 페이지 이미지화 → image block 배열</li>
 *   <li>PAGE_IMAGES 인데 이미지도 미지원   → TEXT_FALLBACK: 호출부가 PdfTextExtractor 로 폴백</li>
 * </ol>
 *
 * 게이트 판단만 하고 블록 객체는 만들지 않는다(호출부마다 ContentBlock / ToolMessageBlock 으로
 * 변환). 진입점: ChatController.resolveDocumentBlock, ParseDocumentTool.
 */
@Component
public class PdfVisionResolver {

    private static final Logger log = LoggerFactory.getLogger(PdfVisionResolver.class);

    private final MCPRagClient ragClient;

    private final long inlineMaxBytes;
    private final long targetRawMaxBytes;
    private final int maxPagesPerRead;
    private final int imageDpi;

    public PdfVisionResolver(MCPRagClient ragClient,
                             @Value("${aimbase.pdf.inline-max-bytes:3145728}") long inlineMaxBytes,
                             @Value("${aimbase.pdf.target-raw-max-bytes:20971520}") long targetRawMaxBytes,
                             @Value("${aimbase.pdf.max-pages-per-read:20}") int maxPagesPerRead,
                             @Value("${aimbase.pdf.image-dpi:100}") int imageDpi) {
        this.ragClient = ragClient;
        this.inlineMaxBytes = inlineMaxBytes;
        this.targetRawMaxBytes = targetRawMaxBytes;
        this.maxPagesPerRead = maxPagesPerRead;
        this.imageDpi = imageDpi;
    }

    public enum Mode { INLINE_PDF, PAGE_IMAGES, TEXT_FALLBACK }

    /** 페이지 이미지 1장 — base64 JPEG. */
    public record PageImage(int pageNumber, String mediaType, String base64) {}

    /**
     * 비전 처리 결과.
     *
     * @param mode      INLINE_PDF / PAGE_IMAGES / TEXT_FALLBACK
     * @param pdfBase64 INLINE_PDF 일 때 base64 PDF (그 외 null)
     * @param images    PAGE_IMAGES 일 때 페이지 이미지 목록 (그 외 빈 리스트)
     * @param note      축약/절삭 등 안내 메시지 (nullable)
     */
    public record PdfVisionResult(Mode mode, String pdfBase64, List<PageImage> images, String note) {}

    /**
     * PDF 바이트 → 비전 처리 모드 결정 + 필요 시 사이드카로 페이지 이미지화.
     *
     * @param pdfBytes      PDF 원본
     * @param supportsPdf   어댑터의 네이티브 PDF document block 지원 여부
     * @param supportsImage 어댑터의 이미지 입력 지원 여부
     */
    public PdfVisionResult resolve(byte[] pdfBytes, boolean supportsPdf, boolean supportsImage) {
        // openclaude: shouldExtractPages = !isPDFSupported() || size > PDF_EXTRACT_SIZE_THRESHOLD
        boolean inline = supportsPdf
                && pdfBytes.length <= inlineMaxBytes
                && pdfBytes.length <= targetRawMaxBytes;

        if (inline) {
            String base64 = Base64.getEncoder().encodeToString(pdfBytes);
            return new PdfVisionResult(Mode.INLINE_PDF, base64, List.of(), null);
        }

        // 페이지 이미지화 — 단, 이미지조차 미지원이면 텍스트 폴백 신호
        if (!supportsImage) {
            log.info("CR-095: adapter supports neither PDF nor image — falling back to text extraction (pdf bytes={})",
                    pdfBytes.length);
            return new PdfVisionResult(Mode.TEXT_FALLBACK, null, List.of(),
                    "adapter does not support PDF or image input");
        }

        String base64 = Base64.getEncoder().encodeToString(pdfBytes);
        Map<String, Object> result;
        try {
            result = ragClient.pdfToImages(base64, "", imageDpi, maxPagesPerRead);
        } catch (Exception e) {
            log.warn("CR-095: pdf_to_images sidecar call failed — falling back to text: {}", e.getMessage());
            return new PdfVisionResult(Mode.TEXT_FALLBACK, null, List.of(), "page rendering failed: " + e.getMessage());
        }

        if (!Boolean.TRUE.equals(result.get("success"))) {
            log.warn("CR-095: pdf_to_images returned failure: {}", result.get("error"));
            return new PdfVisionResult(Mode.TEXT_FALLBACK, null, List.of(),
                    "page rendering failed: " + result.get("error"));
        }

        List<PageImage> images = extractImages(result);
        if (images.isEmpty()) {
            return new PdfVisionResult(Mode.TEXT_FALLBACK, null, List.of(), "no pages rendered");
        }

        String note = Boolean.TRUE.equals(result.get("truncated"))
                ? "PDF truncated to first " + images.size() + " page(s)"
                : null;
        return new PdfVisionResult(Mode.PAGE_IMAGES, null, images, note);
    }

    private List<PageImage> extractImages(Map<String, Object> result) {
        Object imagesObj = result.get("images");
        if (!(imagesObj instanceof List<?> list)) return List.of();
        List<PageImage> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Object data = m.get("data");
                if (!(data instanceof String s) || s.isBlank()) continue;
                int pageNumber = m.get("page_number") instanceof Number n ? n.intValue() : out.size() + 1;
                String mediaType = m.get("media_type") instanceof String mt ? mt : "image/jpeg";
                out.add(new PageImage(pageNumber, mediaType, s));
            }
        }
        return out;
    }
}
