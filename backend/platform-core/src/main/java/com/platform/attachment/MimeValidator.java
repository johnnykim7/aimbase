package com.platform.attachment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * CR-061 파일 MIME 타입 재검증 — magic number 기반.
 *
 * 확장자·Content-Type 헤더는 위조 가능하므로 파일 선두 바이트로 판정한다.
 * 위젯에서 수용하는 5종: PNG / JPEG / GIF / WEBP / PDF.
 */
@Component
public class MimeValidator {

    public static final String PNG  = "image/png";
    public static final String JPEG = "image/jpeg";
    public static final String GIF  = "image/gif";
    public static final String WEBP = "image/webp";
    public static final String PDF  = "application/pdf";

    /**
     * 매직넘버로 실제 MIME 을 판정한다. 위젯 지원 5종이 아니면
     * {@link AttachmentException#CODE_MIME_UNSUPPORTED} 를 던진다.
     *
     * @param head 파일 선두 최소 12 바이트
     */
    public String detect(byte[] head) {
        if (head == null || head.length < 4) {
            throw new AttachmentException(HttpStatus.BAD_REQUEST,
                    AttachmentException.CODE_MIME_UNSUPPORTED,
                    "file is empty or too short to detect media type");
        }

        // PNG — 89 50 4E 47
        if (head[0] == (byte)0x89 && head[1] == 0x50 && head[2] == 0x4E && head[3] == 0x47) {
            return PNG;
        }
        // JPEG — FF D8 FF
        if (head[0] == (byte)0xFF && head[1] == (byte)0xD8 && head[2] == (byte)0xFF) {
            return JPEG;
        }
        // GIF — 47 49 46 38
        if (head[0] == 0x47 && head[1] == 0x49 && head[2] == 0x46 && head[3] == 0x38) {
            return GIF;
        }
        // PDF — 25 50 44 46 (%PDF)
        if (head[0] == 0x25 && head[1] == 0x50 && head[2] == 0x44 && head[3] == 0x46) {
            return PDF;
        }
        // WEBP — RIFF....WEBP (bytes 0..3 = RIFF, 8..11 = WEBP)
        if (head.length >= 12
                && head[0] == 0x52 && head[1] == 0x49 && head[2] == 0x46 && head[3] == 0x46
                && head[8] == 0x57 && head[9] == 0x45 && head[10] == 0x42 && head[11] == 0x50) {
            return WEBP;
        }

        throw new AttachmentException(HttpStatus.BAD_REQUEST,
                AttachmentException.CODE_MIME_UNSUPPORTED,
                "unsupported media type — widget accepts PNG/JPEG/GIF/WEBP/PDF only");
    }

    /**
     * detect() 결과와 Content-Type 헤더가 불일치하면 CODE_MIME_MISMATCH.
     */
    public void assertMatchesContentType(String detected, String contentType) {
        if (contentType == null || contentType.isBlank()) return; // 헤더 없으면 detect 값으로 채택
        String simple = contentType.split(";")[0].trim().toLowerCase();
        if (!simple.equals(detected)) {
            throw new AttachmentException(HttpStatus.UNPROCESSABLE_ENTITY,
                    AttachmentException.CODE_MIME_MISMATCH,
                    "detected=" + detected + " but Content-Type=" + simple);
        }
    }

    public static boolean isImage(String mediaType) {
        return PNG.equals(mediaType) || JPEG.equals(mediaType)
                || GIF.equals(mediaType) || WEBP.equals(mediaType);
    }

    public static boolean isPdf(String mediaType) {
        return PDF.equals(mediaType);
    }
}
