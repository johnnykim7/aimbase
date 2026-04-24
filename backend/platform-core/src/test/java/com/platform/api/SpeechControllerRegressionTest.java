package com.platform.api;

import com.platform.repository.ConnectionRepository;
import com.platform.speech.SpeechException;
import com.platform.speech.SpeechService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * CR-060 Phase 2 — SpeechController 리팩터 후 기존 /api/v1/speech/stt Platform 경로가
 * 종전과 동등한 응답을 내는지 회귀 검증.
 *
 * WhisperSpeechService 를 모킹해 상위 계층(컨트롤러)의 에러코드 → HTTP 매핑만 대상으로 한다.
 * Whisper 호출 자체는 WhisperSpeechServiceTest 에서 별도로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class SpeechControllerRegressionTest {

    @Mock private ConnectionRepository connectionRepository;
    @Mock private SpeechService speechService;

    @Test
    void returnsTextAndLanguageAndDuration_whenServiceSucceeds() throws Exception {
        SpeechController controller = new SpeechController(connectionRepository, speechService);
        when(speechService.transcribe(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn(new SpeechService.TranscribeResult("안녕하세요", "ko", 3.4));

        MockMultipartFile file = new MockMultipartFile(
                "file", "clip.webm", "audio/webm", new byte[] { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3 });

        ResponseEntity<ApiResponse<Map<String, Object>>> res =
                controller.speechToText(file, "whisper-1", "ko");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        ApiResponse<Map<String, Object>> body = Objects.requireNonNull(res.getBody());
        assertThat(body.success()).isTrue();
        assertThat(body.data())
                .containsEntry("text", "안녕하세요")
                .containsEntry("language", "ko")
                .containsEntry("duration", 3.4);
    }

    @Test
    void returns503_whenProviderUnavailable() throws Exception {
        SpeechController controller = new SpeechController(connectionRepository, speechService);
        when(speechService.transcribe(any(byte[].class), any(), any(), anyString()))
                .thenThrow(SpeechException.providerUnavailable());

        MockMultipartFile file = new MockMultipartFile(
                "file", "clip.webm", "audio/webm", new byte[] { 1, 2, 3 });

        ResponseEntity<ApiResponse<Map<String, Object>>> res =
                controller.speechToText(file, "whisper-1", "ko");

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(Objects.requireNonNull(res.getBody()).success()).isFalse();
    }

    @Test
    void returns502_whenUpstreamError() throws Exception {
        SpeechController controller = new SpeechController(connectionRepository, speechService);
        when(speechService.transcribe(any(byte[].class), any(), any(), anyString()))
                .thenThrow(SpeechException.upstream(500, "boom"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "clip.webm", "audio/webm", new byte[] { 1, 2, 3 });

        ResponseEntity<ApiResponse<Map<String, Object>>> res =
                controller.speechToText(file, "whisper-1", "ko");

        assertThat(res.getStatusCode().value()).isEqualTo(502);
    }

    @Test
    void returns504_whenTimeout() throws Exception {
        SpeechController controller = new SpeechController(connectionRepository, speechService);
        when(speechService.transcribe(any(byte[].class), any(), any(), anyString()))
                .thenThrow(SpeechException.timeout(new java.net.http.HttpTimeoutException("slow")));

        MockMultipartFile file = new MockMultipartFile(
                "file", "clip.webm", "audio/webm", new byte[] { 1, 2, 3 });

        ResponseEntity<ApiResponse<Map<String, Object>>> res =
                controller.speechToText(file, "whisper-1", "ko");

        assertThat(res.getStatusCode().value()).isEqualTo(504);
    }
}
