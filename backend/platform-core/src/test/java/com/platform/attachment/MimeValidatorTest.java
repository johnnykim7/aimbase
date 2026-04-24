package com.platform.attachment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CR-061 MimeValidator — magic number 판정 + Content-Type 이중 검증.
 */
class MimeValidatorTest {

    private MimeValidator validator;

    @BeforeEach
    void setUp() {
        validator = new MimeValidator();
    }

    @Test
    void detect_png() {
        byte[] png = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        assertThat(validator.detect(png)).isEqualTo(MimeValidator.PNG);
    }

    @Test
    void detect_jpeg() {
        byte[] jpeg = new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0};
        assertThat(validator.detect(jpeg)).isEqualTo(MimeValidator.JPEG);
    }

    @Test
    void detect_gif() {
        byte[] gif = new byte[]{0x47, 0x49, 0x46, 0x38, 0x39, 0x61};
        assertThat(validator.detect(gif)).isEqualTo(MimeValidator.GIF);
    }

    @Test
    void detect_webp() {
        byte[] webp = new byte[]{
                0x52, 0x49, 0x46, 0x46,  // RIFF
                0x00, 0x00, 0x00, 0x00,  // size (any)
                0x57, 0x45, 0x42, 0x50,  // WEBP
        };
        assertThat(validator.detect(webp)).isEqualTo(MimeValidator.WEBP);
    }

    @Test
    void detect_pdf() {
        byte[] pdf = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D};  // %PDF-
        assertThat(validator.detect(pdf)).isEqualTo(MimeValidator.PDF);
    }

    @Test
    void detect_rejectsUnknownMagic() {
        byte[] bin = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04};
        assertThatThrownBy(() -> validator.detect(bin))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining(AttachmentException.CODE_MIME_UNSUPPORTED);
    }

    @Test
    void detect_rejectsTooShort() {
        assertThatThrownBy(() -> validator.detect(new byte[]{0x25}))
                .isInstanceOf(AttachmentException.class);
    }

    @Test
    void assertMatchesContentType_throwsOnMismatch() {
        assertThatThrownBy(() -> validator.assertMatchesContentType(MimeValidator.PNG, "image/jpeg"))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining(AttachmentException.CODE_MIME_MISMATCH);
    }

    @Test
    void assertMatchesContentType_acceptsWhenHeaderMissing() {
        validator.assertMatchesContentType(MimeValidator.PNG, null);
        validator.assertMatchesContentType(MimeValidator.PNG, "");
    }

    @Test
    void assertMatchesContentType_ignoresCharsetSuffix() {
        validator.assertMatchesContentType(MimeValidator.PDF, "application/pdf; charset=UTF-8");
    }

    @Test
    void isImage_recognizes4() {
        assertThat(MimeValidator.isImage(MimeValidator.PNG)).isTrue();
        assertThat(MimeValidator.isImage(MimeValidator.JPEG)).isTrue();
        assertThat(MimeValidator.isImage(MimeValidator.GIF)).isTrue();
        assertThat(MimeValidator.isImage(MimeValidator.WEBP)).isTrue();
        assertThat(MimeValidator.isImage(MimeValidator.PDF)).isFalse();
    }

    @Test
    void attachmentException_preservesHttpStatus() {
        ResponseStatusException ex = new AttachmentException(
                org.springframework.http.HttpStatus.CONFLICT,
                AttachmentException.CODE_COUNT_EXCEEDED,
                "too many");
        assertThat(ex.getStatusCode().value()).isEqualTo(409);
    }
}
