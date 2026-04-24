package com.platform.speech;

import org.springframework.stereotype.Component;

/**
 * CR-060 오디오 MIME 검증 — magic number 기반.
 *
 * 위젯 STT 가 수용하는 5종: WebM / MP4 / MP3 / WAV / Ogg.
 * 확장자·Content-Type 헤더는 위조 가능하므로 파일 선두 바이트로 판정한다.
 * CR-061 {@code attachment.MimeValidator} 와 도메인을 분리해 Speech 전용 예외 체계를 유지한다.
 */
@Component
public class AudioMimeValidator {

    public static final String WEBM = "audio/webm";
    public static final String MP4  = "audio/mp4";
    public static final String MP3  = "audio/mpeg";
    public static final String WAV  = "audio/wav";
    public static final String OGG  = "audio/ogg";

    /**
     * 선두 바이트로 오디오 MIME 을 판정한다. 모르는 포맷이면 {@link IllegalArgumentException}.
     *
     * @param head 최소 12 바이트 이상의 파일 선두
     */
    public String detect(byte[] head) {
        if (head == null || head.length < 4) {
            throw new IllegalArgumentException("audio payload too short");
        }

        // WebM / Matroska — 1A 45 DF A3 (EBML)
        if (head[0] == 0x1A && head[1] == 0x45 && head[2] == (byte)0xDF && head[3] == (byte)0xA3) {
            return WEBM;
        }
        // Ogg — 4F 67 67 53 (OggS)
        if (head[0] == 0x4F && head[1] == 0x67 && head[2] == 0x67 && head[3] == 0x53) {
            return OGG;
        }
        // MP3 — ID3 (49 44 33) or frame sync (FF Fx)
        if (head[0] == 0x49 && head[1] == 0x44 && head[2] == 0x33) {
            return MP3;
        }
        if (head[0] == (byte)0xFF && (head[1] == (byte)0xFB || head[1] == (byte)0xF3 || head[1] == (byte)0xF2)) {
            return MP3;
        }
        // WAV — RIFF....WAVE (bytes 0..3 = 'RIFF', 8..11 = 'WAVE')
        if (head.length >= 12
                && head[0] == 0x52 && head[1] == 0x49 && head[2] == 0x46 && head[3] == 0x46
                && head[8] == 0x57 && head[9] == 0x41 && head[10] == 0x56 && head[11] == 0x45) {
            return WAV;
        }
        // MP4 / M4A — ISO Base Media File Format: bytes 4..7 = 'ftyp'
        if (head.length >= 8
                && head[4] == 0x66 && head[5] == 0x74 && head[6] == 0x79 && head[7] == 0x70) {
            return MP4;
        }

        throw new IllegalArgumentException("unsupported audio mime — widget accepts webm/mp4/mp3/wav/ogg only");
    }

    /**
     * detect() 결과가 허용 목록에 포함되는지 검사.
     *
     * @param detected       detect() 반환값
     * @param allowedCsvList {@code widget.stt.allowed-mime-types} 를 split 한 목록
     */
    public boolean isAllowed(String detected, java.util.List<String> allowedCsvList) {
        if (detected == null || allowedCsvList == null) return false;
        return allowedCsvList.stream().anyMatch(m -> m.trim().equalsIgnoreCase(detected));
    }
}
