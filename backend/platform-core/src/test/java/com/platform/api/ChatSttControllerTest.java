package com.platform.api;

import com.platform.config.PlatformSettingsService;
import com.platform.policy.AuditLogger;
import com.platform.speech.AudioMimeValidator;
import com.platform.speech.SpeechException;
import com.platform.speech.SpeechService;
import com.platform.speech.SttRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-060 ChatSttController 검증 커버리지:
 *  - 정상 경로 → 200 + audit_log 기록
 *  - Rate limit 초과 → 429
 *  - MIME 판정 실패 → 400
 *  - 허용 MIME 화이트리스트 미매칭 → 400
 *  - 파일 크기 BIZ-103 초과 → 400
 *  - 녹음 시간 BIZ-102 초과 → 400
 *  - Whisper 503 (Connection 없음) → 503
 *  - 빈 파일 → 400
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatSttControllerTest {

    private static final byte[] WEBM_HEAD = new byte[] { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0 };

    @Mock private SpeechService speechService;
    @Mock private SttRateLimiter rateLimiter;
    @Mock private AudioMimeValidator mimeValidator;
    @Mock private PlatformSettingsService settings;
    @Mock private AuditLogger auditLogger;

    private ChatSttController newController() {
        return new ChatSttController(speechService, rateLimiter, mimeValidator, settings, auditLogger);
    }

    private MockMultipartFile webmFile() {
        return new MockMultipartFile("file", "clip.webm", "audio/webm", WEBM_HEAD);
    }

    private void stubHappyPathSettings() {
        when(settings.getLong(eq("widget.stt.max-size-bytes"), anyLong())).thenReturn(26_214_400L);
        when(settings.getStringList(eq("widget.stt.allowed-mime-types"), any()))
                .thenReturn(List.of("audio/webm", "audio/mp4", "audio/mpeg"));
        when(settings.getString(eq("widget.stt.default-language"), anyString())).thenReturn("auto");
        when(settings.getLong(eq("widget.stt.max-duration-seconds"), anyLong())).thenReturn(60L);
        when(mimeValidator.detect(any())).thenReturn("audio/webm");
        when(mimeValidator.isAllowed(eq("audio/webm"), any())).thenReturn(true);
    }

    @Test
    void returns200AndAuditsOnSuccess() {
        stubHappyPathSettings();
        when(speechService.transcribe(any(), eq("audio/webm"), any(), eq("auto")))
                .thenReturn(new SpeechService.TranscribeResult("hello", "en", 2.5));

        ResponseEntity<ApiResponse<?>> res =
                newController().transcribe(webmFile(), null, "sess-1");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        ApiResponse<?> body = (ApiResponse<?>) Objects.requireNonNull(res.getBody());
        assertThat(body.success()).isTrue();
        verify(auditLogger, times(1))
                .log(eq("stt_transcribe"), eq("whisper"), any(), eq("sess-1"), any(), any());
    }

    @Test
    void returns429OnRateLimit() {
        doThrow(new SttRateLimiter.SttRateLimitExceededException(10, 11L))
                .when(rateLimiter).checkAndIncrement(anyString());

        ResponseEntity<ApiResponse<?>> res =
                newController().transcribe(webmFile(), null, "sess-1");

        assertThat(res.getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void returns400OnEmptyFile() {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.webm", "audio/webm", new byte[0]);
        ResponseEntity<ApiResponse<?>> res = newController().transcribe(empty, null, "sess-1");
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void returns400WhenOverSizeLimit() {
        when(settings.getLong(eq("widget.stt.max-size-bytes"), anyLong())).thenReturn(3L);
        // WEBM_HEAD 는 8바이트 — 제한 3바이트 초과
        ResponseEntity<ApiResponse<?>> res =
                newController().transcribe(webmFile(), null, "sess-1");

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(((ApiResponse<?>) Objects.requireNonNull(res.getBody())).error())
                .contains("STT_FILE_TOO_LARGE");
    }

    @Test
    void returns400OnInvalidMagicNumber() {
        when(settings.getLong(eq("widget.stt.max-size-bytes"), anyLong())).thenReturn(26_214_400L);
        when(mimeValidator.detect(any())).thenThrow(new IllegalArgumentException("unsupported audio mime"));

        ResponseEntity<ApiResponse<?>> res =
                newController().transcribe(webmFile(), null, "sess-1");

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(((ApiResponse<?>) Objects.requireNonNull(res.getBody())).error())
                .contains("STT_INVALID_MIME");
    }

    @Test
    void returns400WhenMimeNotInAllowList() {
        when(settings.getLong(eq("widget.stt.max-size-bytes"), anyLong())).thenReturn(26_214_400L);
        when(mimeValidator.detect(any())).thenReturn("audio/flac");
        when(settings.getStringList(eq("widget.stt.allowed-mime-types"), any()))
                .thenReturn(List.of("audio/webm"));
        when(mimeValidator.isAllowed(eq("audio/flac"), any())).thenReturn(false);

        ResponseEntity<ApiResponse<?>> res =
                newController().transcribe(webmFile(), null, "sess-1");

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(((ApiResponse<?>) Objects.requireNonNull(res.getBody())).error())
                .contains("not in allowed list");
    }

    @Test
    void returns400WhenDurationExceedsLimit() {
        stubHappyPathSettings();
        when(settings.getLong(eq("widget.stt.max-duration-seconds"), anyLong())).thenReturn(10L);
        when(speechService.transcribe(any(), anyString(), any(), anyString()))
                .thenReturn(new SpeechService.TranscribeResult("...", "en", 30.0));

        ResponseEntity<ApiResponse<?>> res =
                newController().transcribe(webmFile(), null, "sess-1");

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(((ApiResponse<?>) Objects.requireNonNull(res.getBody())).error())
                .contains("STT_FILE_TOO_LONG");
    }

    @Test
    void returns503WhenProviderUnavailable() {
        stubHappyPathSettings();
        when(speechService.transcribe(any(), anyString(), any(), anyString()))
                .thenThrow(SpeechException.providerUnavailable());

        ResponseEntity<ApiResponse<?>> res =
                newController().transcribe(webmFile(), null, "sess-1");

        assertThat(res.getStatusCode().value()).isEqualTo(503);
    }
}
