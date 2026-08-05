package com.platform.speech;

import com.platform.config.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CR-134: STT 공급자 선택·폴백 테스트.
 *
 * 핵심: 기본은 로컬(비용 0)이고, 맥이 꺼져 로컬이 실패해도 위젯 음성입력이 죽지 않아야 한다.
 */
class SpeechServiceRouterTest {

    private LocalWhisperSpeechService local;
    private WhisperSpeechService openai;
    private PlatformSettingsService settings;
    private SpeechServiceRouter router;

    private final byte[] audio = "a".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        local = mock(LocalWhisperSpeechService.class);
        openai = mock(WhisperSpeechService.class);
        settings = mock(PlatformSettingsService.class);
        // 기본값을 그대로 돌려주는 설정 스텁
        when(settings.getString(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        router = new SpeechServiceRouter(local, openai, settings);
    }

    private SpeechService.TranscribeResult result(String text) {
        return new SpeechService.TranscribeResult(text, "ko", 10.0);
    }

    @Test
    @DisplayName("기본은 로컬을 쓴다(비용 0) — OpenAI 를 호출하지 않는다")
    void defaultsToLocal() {
        when(local.transcribe(any(), any(), any(), any())).thenReturn(result("로컬"));

        var r = router.transcribe(audio, "audio/webm", "a.webm", "ko");

        assertEquals("로컬", r.text());
        verify(openai, never()).transcribe(any(), any(), any(), any());
    }

    @Test
    @DisplayName("provider=openai 설정이면 OpenAI 를 쓴다")
    void honorsOpenAiSetting() {
        when(settings.getString(eq(SpeechServiceRouter.KEY_PROVIDER), anyString()))
                .thenReturn(SpeechServiceRouter.PROVIDER_OPENAI);
        when(openai.transcribe(any(), any(), any(), any())).thenReturn(result("오픈AI"));

        var r = router.transcribe(audio, "audio/webm", "a.webm", "ko");

        assertEquals("오픈AI", r.text());
        verify(local, never()).transcribe(any(), any(), any(), any());
    }

    @Test
    @DisplayName("로컬 실패 시 OpenAI 로 폴백한다(맥이 꺼져도 위젯이 죽지 않는다)")
    void fallsBackWhenLocalFails() {
        when(local.transcribe(any(), any(), any(), any()))
                .thenThrow(SpeechException.providerUnavailable());
        when(openai.transcribe(any(), any(), any(), any())).thenReturn(result("폴백"));

        var r = router.transcribe(audio, "audio/webm", "a.webm", "ko");

        assertEquals("폴백", r.text());
        verify(openai).transcribe(any(), any(), any(), any());
    }

    @Test
    @DisplayName("폴백을 끄면 로컬 실패가 그대로 전파된다")
    void fallbackDisabled_propagates() {
        when(settings.getString(eq(SpeechServiceRouter.KEY_FALLBACK), anyString())).thenReturn("false");
        when(local.transcribe(any(), any(), any(), any()))
                .thenThrow(SpeechException.providerUnavailable());

        assertThrows(SpeechException.class,
                () -> router.transcribe(audio, "audio/webm", "a.webm", "ko"));
        verify(openai, never()).transcribe(any(), any(), any(), any());
    }
}
