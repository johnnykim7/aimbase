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

    // ─────────────────────────────────────────────────────────
    // CR-137 — 영상 4종 (MP4 / MOV / WebM / AVI)
    // ─────────────────────────────────────────────────────────

    /** ftyp + brand "isom" → MP4. bytes 0..3 은 box size 라 값이 무엇이든 무관. */
    @Test
    void detect_mp4_isom() {
        byte[] mp4 = new byte[]{
                0x00, 0x00, 0x00, 0x20,  // box size (any)
                0x66, 0x74, 0x79, 0x70,  // ftyp
                0x69, 0x73, 0x6F, 0x6D,  // isom
        };
        assertThat(validator.detect(mp4)).isEqualTo(MimeValidator.MP4);
    }

    /** Android 등이 쓰는 mp42 brand 도 MP4 로 떨어져야 한다. */
    @Test
    void detect_mp4_mp42Brand() {
        byte[] mp4 = new byte[]{
                0x00, 0x00, 0x00, 0x18,
                0x66, 0x74, 0x79, 0x70,  // ftyp
                0x6D, 0x70, 0x34, 0x32,  // mp42
        };
        assertThat(validator.detect(mp4)).isEqualTo(MimeValidator.MP4);
    }

    /** iOS 는 .mov 도 ftyp 를 쓴다 — brand "qt  " 로만 갈린다. */
    @Test
    void detect_mov_quicktimeBrand() {
        byte[] mov = new byte[]{
                0x00, 0x00, 0x00, 0x14,
                0x66, 0x74, 0x79, 0x70,  // ftyp
                0x71, 0x74, 0x20, 0x20,  // "qt  " (뒤 2바이트 공백)
        };
        assertThat(validator.detect(mov)).isEqualTo(MimeValidator.MOV);
    }

    @Test
    void detect_webm() {
        byte[] webm = new byte[]{(byte)0x1A, 0x45, (byte)0xDF, (byte)0xA3, 0x01, 0x02};
        assertThat(validator.detect(webm)).isEqualTo(MimeValidator.WEBM);
    }

    /** AVI 는 WEBP 와 같은 RIFF 컨테이너 — 8..11 로 갈린다("AVI " 끝 공백 주의). */
    @Test
    void detect_avi_notConfusedWithWebp() {
        byte[] avi = new byte[]{
                0x52, 0x49, 0x46, 0x46,  // RIFF
                0x00, 0x00, 0x00, 0x00,  // size (any)
                0x41, 0x56, 0x49, 0x20,  // "AVI "
        };
        assertThat(validator.detect(avi)).isEqualTo(MimeValidator.AVI);
    }

    /** RIFF 인데 WEBP 도 AVI 도 아니면 통과시키지 않는다. */
    @Test
    void detect_riffUnknownSubtype_rejected() {
        byte[] riffWave = new byte[]{
                0x52, 0x49, 0x46, 0x46,
                0x00, 0x00, 0x00, 0x00,
                0x57, 0x41, 0x56, 0x45,  // "WAVE"
        };
        assertThatThrownBy(() -> validator.detect(riffWave))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining(AttachmentException.CODE_MIME_UNSUPPORTED);
    }

    @Test
    void isVideo_recognizes4() {
        assertThat(MimeValidator.isVideo(MimeValidator.MP4)).isTrue();
        assertThat(MimeValidator.isVideo(MimeValidator.MOV)).isTrue();
        assertThat(MimeValidator.isVideo(MimeValidator.WEBM)).isTrue();
        assertThat(MimeValidator.isVideo(MimeValidator.AVI)).isTrue();
        assertThat(MimeValidator.isVideo(MimeValidator.JPEG)).isFalse();
        assertThat(MimeValidator.isVideo(MimeValidator.PDF)).isFalse();
    }

    /**
     * 영상은 헤더 표기가 OS·브라우저마다 달라 계열만 맞으면 통과시킨다.
     * (iOS 가 .mov 를 video/mp4 로 보내는 등 — 엄격 비교하면 정상 파일이 422 로 튕긴다)
     */
    @Test
    void assertMatchesContentType_video_allowsCrossVideoSubtype() {
        validator.assertMatchesContentType(MimeValidator.MOV, "video/mp4");
        validator.assertMatchesContentType(MimeValidator.MP4, "video/3gpp");
        validator.assertMatchesContentType(MimeValidator.WEBM, "video/x-matroska");
    }

    @Test
    void assertMatchesContentType_video_allowsGenericOctetStream() {
        validator.assertMatchesContentType(MimeValidator.MP4, "application/octet-stream");
        validator.assertMatchesContentType(MimeValidator.MP4, "binary/octet-stream");
    }

    /** 완화는 영상에만 적용된다 — 이미지끼리는 여전히 엄격해야 회귀가 안 난다. */
    @Test
    void assertMatchesContentType_image_stillStrict() {
        assertThatThrownBy(() -> validator.assertMatchesContentType(MimeValidator.PNG, "image/webp"))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining(AttachmentException.CODE_MIME_MISMATCH);
        assertThatThrownBy(() -> validator.assertMatchesContentType(MimeValidator.MP4, "image/png"))
                .isInstanceOf(AttachmentException.class)
                .hasMessageContaining(AttachmentException.CODE_MIME_MISMATCH);
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
