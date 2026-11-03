package com.platform.speech;

import com.platform.config.PlatformSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * CR-165: 음성 합성 길 나누기(BIZ-136).
 *
 * <p>목소리 목록({@link TtsVoiceCatalog})에 있는 목소리만 맥 음성 엔진으로 보낸다. 목록 밖 목소리와
 * 빈 목소리는 CR-165 이전처럼 OpenAI 로만 보내고 글도 바꾸지 않는다. 다른 소비처가 지금 쓰는
 * OpenAI 목소리({@code nova} 등)가 그대로 돌게 하기 위해서다.
 *
 * <p>맥 엔진을 먼저 쓰고 실패하면 OpenAI 로 전환하는 것은 받아쓰기({@link SpeechServiceRouter})와 같다.
 * 맥이 잠들어도 음성 브리핑이 멈추지 않게 하기 위해서다.
 */
@Service
public class TtsServiceRouter {

    private static final Logger log = LoggerFactory.getLogger(TtsServiceRouter.class);

    /** 운영 중 바꿀 수 있도록 global_config 로 뺀다(CR-040 설정 관리). */
    public static final String KEY_PROVIDER = "tts.provider";
    public static final String KEY_FALLBACK = "tts.fallback-to-openai";

    public static final String PROVIDER_LOCAL = "local";
    public static final String PROVIDER_OPENAI = "openai";

    /** 응답 머리글 {@code X-Aimbase-TTS-Engine} 값. */
    public static final String ENGINE_LOCAL = "local";
    public static final String ENGINE_OPENAI = "openai";

    private final LocalTtsService local;
    private final OpenAiTtsService openai;
    private final TtsVoiceCatalog catalog;
    private final PlatformSettingsService settings;

    public TtsServiceRouter(LocalTtsService local,
                            OpenAiTtsService openai,
                            TtsVoiceCatalog catalog,
                            PlatformSettingsService settings) {
        this.local = local;
        this.openai = openai;
        this.catalog = catalog;
        this.settings = settings;
    }

    /** status 가 400 이상이면 body 는 OpenAI 가 돌려준 오류 본문이다. */
    public record Result(int status, byte[] body, String engine) {}

    /**
     * @throws SpeechException 맥 엔진이 실패했는데 OpenAI 전환이 꺼져 있거나, OpenAI 연결이 없을 때
     *                         {@code PROVIDER_UNAVAILABLE}. OpenAI 로 요청을 보내지 못했을 때 {@code UPSTREAM_ERROR}
     */
    public Result synthesize(String text, String model, String voice, Double speed) {
        Optional<TtsVoiceCatalog.Voice> listed = catalog.find(voice);
        if (listed.isEmpty()) {
            return viaOpenAi(text, model, voice, speed);
        }

        TtsVoiceCatalog.Voice v = listed.get();
        String spoken = TtsTextNormalizer.normalize(text);

        if (!PROVIDER_OPENAI.equalsIgnoreCase(settings.getString(KEY_PROVIDER, PROVIDER_LOCAL))) {
            try {
                byte[] audio = local.synthesize(spoken, v.id(),
                        speed != null ? speed : TtsVoiceCatalog.NORMAL_SPEED);
                return new Result(200, audio, ENGINE_LOCAL);
            } catch (SpeechException e) {
                if (!fallbackEnabled()) {
                    throw new SpeechException(SpeechException.ErrorCode.PROVIDER_UNAVAILABLE,
                            "맥 음성 엔진 실패, OpenAI 전환 꺼짐: " + e.getMessage(), e);
                }
                log.warn("[CR-165] 맥 음성 엔진 실패({}), OpenAI 로 전환: {}", e.getCode(), e.getMessage());
            }
        }
        String openaiVoice = v.openaiVoice() != null && !v.openaiVoice().isBlank() ? v.openaiVoice() : null;
        return viaOpenAi(spoken, model, openaiVoice, speed);
    }

    private Result viaOpenAi(String text, String model, String voice, Double speed) {
        OpenAiTtsService.Result r = openai.synthesize(text, model, voice, speed);
        return new Result(r.status(), r.body(), ENGINE_OPENAI);
    }

    private boolean fallbackEnabled() {
        return !"false".equalsIgnoreCase(settings.getString(KEY_FALLBACK, "true"));
    }
}
