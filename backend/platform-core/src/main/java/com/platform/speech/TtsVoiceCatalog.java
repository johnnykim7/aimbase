package com.platform.speech;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.PlatformSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * CR-165: 맥 음성 엔진으로 보낼 목소리 목록과 speed 범위(BIZ-136).
 *
 * <p>목록은 플랫폼 설정 {@code tts.voices} 에 JSON 배열로 둔다. 목소리 이름을 바꾸거나 목소리를 늘릴 때
 * 소스를 고치지 않기 위해서다. 목록에 없는 목소리는 지금처럼 OpenAI 로만 가므로, 목록이 비면
 * CR-165 이전과 같은 동작이 된다.
 */
@Component
public class TtsVoiceCatalog {

    private static final Logger log = LoggerFactory.getLogger(TtsVoiceCatalog.class);

    public static final String KEY_VOICES = "tts.voices";
    public static final String KEY_SPEED_MIN = "tts.speed-min";
    public static final String KEY_SPEED_MAX = "tts.speed-max";

    /** OpenAI 음성 합성 speed 범위(0.25 ~ 4.0) 안에서, 맥 엔진 atempo 로 소리가 덜 뭉개지는 범위. */
    static final double DEFAULT_SPEED_MIN = 0.5;
    static final double DEFAULT_SPEED_MAX = 2.0;

    /** 원래 빠르기. 요청에 speed 가 없을 때 쓴다. */
    public static final double NORMAL_SPEED = 1.0;

    private static final TypeReference<List<Voice>> VOICE_LIST = new TypeReference<>() {};

    private final PlatformSettingsService settings;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TtsVoiceCatalog(PlatformSettingsService settings) {
        this.settings = settings;
    }

    /**
     * @param openaiVoice 맥 엔진이 실패해 OpenAI 로 갈 때 쓰는 OpenAI 목소리. 목록 API 로 내보내지 않는다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Voice(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("openai_voice") String openaiVoice,
            @JsonProperty("default") boolean isDefault) {}

    public List<Voice> voices() {
        String raw = settings.getString(KEY_VOICES, null);
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<Voice> parsed = objectMapper.readValue(raw, VOICE_LIST);
            return parsed == null ? List.of() : parsed.stream()
                    .filter(Objects::nonNull)
                    .filter(v -> v.id() != null && !v.id().isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("[CR-165] {} 설정을 JSON 으로 읽지 못해 빈 목록으로 본다: {}", KEY_VOICES, e.getMessage());
            return List.of();
        }
    }

    public Optional<Voice> find(String voiceId) {
        if (voiceId == null || voiceId.isBlank()) return Optional.empty();
        return voices().stream().filter(v -> v.id().equals(voiceId)).findFirst();
    }

    public double speedMin() {
        return settings.getDouble(KEY_SPEED_MIN, DEFAULT_SPEED_MIN);
    }

    public double speedMax() {
        return settings.getDouble(KEY_SPEED_MAX, DEFAULT_SPEED_MAX);
    }

    public boolean speedInRange(Double speed) {
        double s = speed != null ? speed : NORMAL_SPEED;
        return s >= speedMin() && s <= speedMax();
    }
}
