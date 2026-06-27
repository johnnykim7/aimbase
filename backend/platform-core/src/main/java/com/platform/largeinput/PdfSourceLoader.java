package com.platform.largeinput;

import com.platform.rag.MCPRagClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;

/**
 * CR-120: PDF 소스 로더. 바이트 + 사이드카 pdf_page_count 로 totalPages 채움.
 */
@Component
public class PdfSourceLoader implements SourceLoader {

    private static final Logger log = LoggerFactory.getLogger(PdfSourceLoader.class);

    private final MCPRagClient ragClient;

    public PdfSourceLoader(MCPRagClient ragClient) {
        this.ragClient = ragClient;
    }

    @Override
    public boolean supports(String mimeType) {
        return mimeType != null && mimeType.toLowerCase().contains("pdf");
    }

    @Override
    public LargeInputSource load(String sourceId, String mimeType, byte[] bytes) {
        Integer totalPages = fetchPageCount(bytes);
        return new LargeInputSource(sourceId, mimeType, bytes, null, totalPages);
    }

    private Integer fetchPageCount(byte[] bytes) {
        try {
            String base64 = Base64.getEncoder().encodeToString(bytes);
            Map<String, Object> r = ragClient.pdfPageCount(base64);
            if (Boolean.TRUE.equals(r.get("success")) && r.get("page_count") instanceof Number n) {
                return n.intValue();
            }
        } catch (Exception e) {
            log.warn("CR-120 PdfSourceLoader: pdf_page_count failed — totalPages unknown: {}", e.getMessage());
        }
        return null;
    }
}
