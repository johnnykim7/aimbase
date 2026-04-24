package com.platform.speech;

import com.platform.repository.ConnectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * CR-060 Phase 2 — Whisper 프록시 서비스의 Connection 조회 경로 단위 검증.
 *
 * HTTP 실호출 경로(multipart 조립 + Whisper 응답 파싱)는 네트워크를 요구해
 * 본 단위 테스트 범위 밖이다. Phase 3 ChatSttController 통합 단계와 수동 E2E 에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class WhisperSpeechServiceTest {

    @Mock private ConnectionRepository connectionRepository;

    @Test
    void throwsProviderUnavailable_whenNoOpenAIConnection() {
        when(connectionRepository.findAll()).thenReturn(List.of());
        WhisperSpeechService svc = new WhisperSpeechService(connectionRepository);

        assertThatThrownBy(() -> svc.transcribe(new byte[] { 1, 2, 3 }, "audio/webm", "a.webm", "ko"))
                .isInstanceOf(SpeechException.class)
                .extracting("code")
                .isEqualTo(SpeechException.ErrorCode.PROVIDER_UNAVAILABLE);
    }
}
