package com.platform.speech;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CR-060 AudioMimeValidator — 5종 오디오 magic number 검출 단위 검증.
 */
class AudioMimeValidatorTest {

    private final AudioMimeValidator validator = new AudioMimeValidator();

    @Test
    void detectsWebmFromEbmlHeader() {
        byte[] head = new byte[] { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0 };
        assertThat(validator.detect(head)).isEqualTo(AudioMimeValidator.WEBM);
    }

    @Test
    void detectsOggFromOggSHeader() {
        byte[] head = new byte[] { 0x4F, 0x67, 0x67, 0x53, 0, 0, 0, 0 };
        assertThat(validator.detect(head)).isEqualTo(AudioMimeValidator.OGG);
    }

    @Test
    void detectsMp3FromId3Header() {
        byte[] head = new byte[] { 0x49, 0x44, 0x33, 0x04, 0, 0, 0, 0 };
        assertThat(validator.detect(head)).isEqualTo(AudioMimeValidator.MP3);
    }

    @Test
    void detectsMp3FromFrameSync() {
        byte[] head = new byte[] { (byte) 0xFF, (byte) 0xFB, 0x10, 0x00, 0, 0, 0, 0 };
        assertThat(validator.detect(head)).isEqualTo(AudioMimeValidator.MP3);
    }

    @Test
    void detectsWavFromRiffWaveHeader() {
        byte[] head = new byte[] {
                0x52, 0x49, 0x46, 0x46, // RIFF
                0, 0, 0, 0,
                0x57, 0x41, 0x56, 0x45  // WAVE
        };
        assertThat(validator.detect(head)).isEqualTo(AudioMimeValidator.WAV);
    }

    @Test
    void detectsMp4FromFtypBox() {
        byte[] head = new byte[] {
                0, 0, 0, 0x20,
                0x66, 0x74, 0x79, 0x70, // ftyp
                0x69, 0x73, 0x6F, 0x6D  // isom
        };
        assertThat(validator.detect(head)).isEqualTo(AudioMimeValidator.MP4);
    }

    @Test
    void rejectsUnknownHeader() {
        byte[] head = new byte[] { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05 };
        assertThatThrownBy(() -> validator.detect(head))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported audio mime");
    }

    @Test
    void rejectsTooShortPayload() {
        assertThatThrownBy(() -> validator.detect(new byte[] { 1, 2 }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void isAllowed_matchesCsvEntries() {
        assertThat(validator.isAllowed("audio/webm",
                List.of("audio/webm", "audio/mp4"))).isTrue();
        assertThat(validator.isAllowed("audio/flac",
                List.of("audio/webm", "audio/mp4"))).isFalse();
    }
}
