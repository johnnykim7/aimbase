package com.platform.attachment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * CR-061 파일 MIME 타입 재검증 — magic number 기반.
 *
 * 확장자·Content-Type 헤더는 위조 가능하므로 파일 선두 바이트로 판정한다.
 * 이미지·문서 5종: PNG / JPEG / GIF / WEBP / PDF.
 * CR-137 추가 영상 4종: MP4 / QuickTime(MOV) / WebM / AVI.
 */
@Component
public class MimeValidator {

    public static final String PNG  = "image/png";
    public static final String JPEG = "image/jpeg";
    public static final String GIF  = "image/gif";
    public static final String WEBP = "image/webp";
    public static final String PDF  = "application/pdf";

    // CR-137: 영상 — 현장 촬영 업로드용
    public static final String MP4  = "video/mp4";
    public static final String MOV  = "video/quicktime";
    public static final String WEBM = "video/webm";
    public static final String AVI  = "video/x-msvideo";

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
        // RIFF 계열 — WEBP 와 AVI 가 같은 RIFF 컨테이너를 쓴다.
        // bytes 0..3 = "RIFF", 8..11 이 포맷을 가른다: "WEBP" vs "AVI ".
        if (head.length >= 12
                && head[0] == 0x52 && head[1] == 0x49 && head[2] == 0x46 && head[3] == 0x46) {
            // WEBP — 8..11 = 57 45 42 50
            if (head[8] == 0x57 && head[9] == 0x45 && head[10] == 0x42 && head[11] == 0x50) {
                return WEBP;
            }
            // CR-137 AVI — 8..11 = 41 56 49 20 ("AVI ", 끝에 공백)
            if (head[8] == 0x41 && head[9] == 0x56 && head[10] == 0x49 && head[11] == 0x20) {
                return AVI;
            }
        }

        // CR-137 WebM (Matroska) — 1A 45 DF A3. Android Chrome 녹화 기본.
        if (head[0] == (byte)0x1A && head[1] == 0x45 && head[2] == (byte)0xDF && head[3] == (byte)0xA3) {
            return WEBM;
        }

        // CR-137 MP4 / QuickTime — ISO BMFF: bytes 4..7 = "ftyp", 8..11 = major brand.
        // iOS 는 .mov 로 찍어도 ftyp 헤더를 쓰므로 brand 로 갈라야 한다.
        //   "qt  " → QuickTime(MOV), 그 외(isom/mp42/avc1/mmp4/iso2...) → MP4
        if (head.length >= 12
                && head[4] == 0x66 && head[5] == 0x74 && head[6] == 0x79 && head[7] == 0x70) {
            if (head[8] == 0x71 && head[9] == 0x74 && head[10] == 0x20 && head[11] == 0x20) {
                return MOV;
            }
            return MP4;
        }

        throw new AttachmentException(HttpStatus.BAD_REQUEST,
                AttachmentException.CODE_MIME_UNSUPPORTED,
                "unsupported media type — accepts PNG/JPEG/GIF/WEBP/PDF, MP4/MOV/WEBM/AVI");
    }

    /**
     * detect() 결과와 Content-Type 헤더가 불일치하면 CODE_MIME_MISMATCH.
     *
     * CR-137: 영상은 예외적으로 "같은 컨테이너 계열"까지 허용한다.
     * ISO BMFF(ftyp) 계열은 브라우저·OS 마다 Content-Type 표기가 제각각이라
     * (iOS 가 .mov 를 video/mp4 로, Android 가 mp4 를 video/3gpp 로 보내는 등)
     * 엄격 비교하면 정상 파일이 422 로 튕긴다. 매직넘버 판정을 신뢰하고 헤더는 계열만 본다.
     */
    public void assertMatchesContentType(String detected, String contentType) {
        if (contentType == null || contentType.isBlank()) return; // 헤더 없으면 detect 값으로 채택
        String simple = contentType.split(";")[0].trim().toLowerCase();
        if (simple.equals(detected)) return;

        // 영상: 매직넘버가 영상이고 헤더도 video/* 면 통과 (세부 표기 차이는 무시)
        if (isVideo(detected) && simple.startsWith("video/")) return;

        // 일부 클라이언트는 영상에 generic 헤더를 붙인다
        if (isVideo(detected)
                && (simple.equals("application/octet-stream") || simple.equals("binary/octet-stream"))) {
            return;
        }

        throw new AttachmentException(HttpStatus.UNPROCESSABLE_ENTITY,
                AttachmentException.CODE_MIME_MISMATCH,
                "detected=" + detected + " but Content-Type=" + simple);
    }

    public static boolean isImage(String mediaType) {
        return PNG.equals(mediaType) || JPEG.equals(mediaType)
                || GIF.equals(mediaType) || WEBP.equals(mediaType);
    }

    public static boolean isPdf(String mediaType) {
        return PDF.equals(mediaType);
    }

    /** CR-137: 프레임 추출 대상인지 판정. */
    public static boolean isVideo(String mediaType) {
        return MP4.equals(mediaType) || MOV.equals(mediaType)
                || WEBM.equals(mediaType) || AVI.equals(mediaType);
    }
}
