package com.platform.speech;

import com.platform.config.PlatformSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CR-160: 음성 변환이 지어낸 글로 보이는 구간에 {@code suspect_reason} 을 붙인다.
 *
 * <p>Whisper 는 알아듣지 못한 녹음이나 소리 없는 녹음에서 하지 않은 말을 지어낸다. 글을 지우면
 * 사람이 확인할 근거도 사라지므로 글은 그대로 두고 표시만 붙여 소비처가 점검으로 올리게 한다.
 *
 * <p>기준값과 문구는 global_config 에서 읽는다. 설정이 없거나 비면 그 표시를 하지 않는다.
 * 키에 {@code threshold}, {@code max-} 를 쓰지 않는 것은 설정 변경 API 가 그런 키를 양의 정수로
 * 검사해 소수 기준값을 받지 않기 때문이다({@link PlatformSettingsService}).
 */
@Component
public class TranscriptSuspectMarker {

    private static final Logger log = LoggerFactory.getLogger(TranscriptSuspectMarker.class);

    public static final String KEY_NO_SPEECH_PROB = "stt.suspect-no-speech-prob";
    public static final String KEY_PHRASES = "stt.suspect-phrases";
    public static final String FIELD = "suspect_reason";

    // 모델이 같은 문구를 띄어쓰기와 문장부호만 바꿔 내므로 비교 전에 뺀다.
    private static final Pattern IGNORED = Pattern.compile("[\\p{Z}\\s\\p{P}]+");

    private final PlatformSettingsService settings;

    public TranscriptSuspectMarker(PlatformSettingsService settings) {
        this.settings = settings;
    }

    /** 구간마다 사본을 만들어 {@code suspect_reason} 을 넣는다. 받은 목록과 구간은 바꾸지 않는다. */
    public List<Map<String, Object>> mark(List<Map<String, Object>> segments) {
        if (segments == null || segments.isEmpty()) {
            return segments;
        }
        Double noSpeechProb = noSpeechProb();
        List<String> phrases = settings.getStringList(KEY_PHRASES, List.of()).stream()
                .map(TranscriptSuspectMarker::normalize)
                .filter(p -> !p.isEmpty())
                .toList();

        List<Map<String, Object>> marked = new ArrayList<>(segments.size());
        for (Map<String, Object> segment : segments) {
            Map<String, Object> copy = new LinkedHashMap<>(segment);
            SegmentSuspectReason reason = reasonOf(segment, noSpeechProb, phrases);
            copy.put(FIELD, reason != null ? reason.name() : null);
            marked.add(copy);
        }
        return marked;
    }

    private static SegmentSuspectReason reasonOf(Map<String, Object> segment, Double noSpeechProb,
                                                 List<String> phrases) {
        Object rawText = segment.get("text");
        String text = rawText != null ? normalize(rawText.toString()) : "";
        // 글이 없는 창은 라이브러리가 이미 소리 없음으로 비운 곳이라 지어낸 말이 없다.
        // 여기까지 표시하면 긴 회의의 쉬는 구간마다 점검이 오른다.
        if (text.isEmpty()) {
            return null;
        }
        if (noSpeechProb != null && segment.get("no_speech_prob") instanceof Number p
                && p.doubleValue() >= noSpeechProb) {
            return SegmentSuspectReason.NO_SPEECH;
        }
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return SegmentSuspectReason.KNOWN_PHRASE;
            }
        }
        return null;
    }

    private Double noSpeechProb() {
        String value = settings.getString(KEY_NO_SPEECH_PROB, null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            log.warn("[CR-160] {} 값이 수가 아니라 소리 없음 표시를 하지 않는다: '{}'", KEY_NO_SPEECH_PROB, value);
            return null;
        }
    }

    static String normalize(String text) {
        return IGNORED.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
