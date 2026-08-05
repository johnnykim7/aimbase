package com.platform.speech;

import com.platform.config.PlatformSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * CR-134: STT 공급자 선택 라우터.
 *
 * <p>엔진은 양쪽 다 Whisper 다. 차이는 <b>비용과 데이터 경계</b>뿐이다.
 * <ul>
 *   <li>{@code local}  — 맥 MLX 사이드카. 비용 0, 오디오가 외부로 나가지 않음. 맥이 꺼지면 불가.</li>
 *   <li>{@code openai} — OpenAI Whisper API. $0.006/분, 오디오가 외부로 나감. 항상 가용.</li>
 * </ul>
 *
 * <p>기본값은 {@code local} 이며, 실패하면 OpenAI 로 폴백한다(설정으로 끌 수 있음).
 * 폴백이 있어야 맥이 잠자기로 들어가도 위젯 음성입력이 죽지 않는다.
 *
 * <p>컨트롤러가 이 빈을 주입받도록 {@code @Primary} 를 둔다. 구현체가 둘이라
 * 이게 없으면 {@code SpeechService} 주입이 모호해져 기동에 실패한다.
 */
@Service
@Primary
public class SpeechServiceRouter implements SpeechService {

    private static final Logger log = LoggerFactory.getLogger(SpeechServiceRouter.class);

    /** 운영 중 바꿀 수 있도록 global_config 로 뺀다(CR-040 설정 관리). */
    public static final String KEY_PROVIDER = "stt.provider";
    public static final String KEY_FALLBACK = "stt.fallback-to-openai";

    public static final String PROVIDER_LOCAL = "local";
    public static final String PROVIDER_OPENAI = "openai";

    private final LocalWhisperSpeechService local;
    private final WhisperSpeechService openai;
    private final PlatformSettingsService settings;

    public SpeechServiceRouter(LocalWhisperSpeechService local,
                               WhisperSpeechService openai,
                               PlatformSettingsService settings) {
        this.local = local;
        this.openai = openai;
        this.settings = settings;
    }

    @Override
    public TranscribeResult transcribe(byte[] audio, String mimeType, String filename, String language) {
        String provider = settings.getString(KEY_PROVIDER, PROVIDER_LOCAL);

        if (PROVIDER_OPENAI.equalsIgnoreCase(provider)) {
            return openai.transcribe(audio, mimeType, filename, language);
        }

        try {
            return local.transcribe(audio, mimeType, filename, language);
        } catch (SpeechException e) {
            // 로컬 사이드카는 맥이 꺼지거나 잠자면 닿지 않는다. 그때도 위젯이 죽으면 안 된다.
            if (!fallbackEnabled()) {
                throw e;
            }
            log.warn("[CR-134] 로컬 STT 실패({}) → OpenAI 폴백: {}", e.getCode(), e.getMessage());
            return openai.transcribe(audio, mimeType, filename, language);
        }
    }

    private boolean fallbackEnabled() {
        return !"false".equalsIgnoreCase(settings.getString(KEY_FALLBACK, "true"));
    }
}
