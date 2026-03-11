package com.platform.rag;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Apache Tika 3.1.0 기반 문서 파서.
 * PDF, DOCX, TXT, HTML, Markdown 등 다양한 포맷 지원.
 */
@Component
public class TikaDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(TikaDocumentParser.class);
    private static final int MAX_TEXT_LENGTH = 5_000_000; // 5MB 텍스트 한도

    private final Tika tika = new Tika();

    @Override
    public String parse(InputStream input, String mimeType) throws IOException {
        try {
            String text = tika.parseToString(input);
            if (text.length() > MAX_TEXT_LENGTH) {
                log.warn("Parsed text truncated from {} to {} chars", text.length(), MAX_TEXT_LENGTH);
                text = text.substring(0, MAX_TEXT_LENGTH);
            }
            return text.strip();
        } catch (TikaException e) {
            throw new IOException("Failed to parse document with Tika: " + e.getMessage(), e);
        }
    }

    @Override
    public String parse(String text) {
        return text != null ? text.strip() : "";
    }
}
