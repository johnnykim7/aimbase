package com.platform.speech;

import com.platform.config.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CR-160: 변환 구간 표시 규칙. 측정값은 개발기 bp-office 녹음(CR-160 문서 2절)에서 가져왔다.
 */
class TranscriptSuspectMarkerTest {

    private PlatformSettingsService settings;
    private TranscriptSuspectMarker marker;

    @BeforeEach
    void setUp() {
        settings = mock(PlatformSettingsService.class);
        configure("0.6", List.of("자막 제공", "시청해 주셔서 감사합니다", "MBC 뉴스"));
        marker = new TranscriptSuspectMarker(settings);
    }

    private void configure(String noSpeechProb, List<String> phrases) {
        when(settings.getString(eq(TranscriptSuspectMarker.KEY_NO_SPEECH_PROB), any())).thenReturn(noSpeechProb);
        when(settings.getStringList(eq(TranscriptSuspectMarker.KEY_PHRASES), any())).thenReturn(phrases);
    }

    private static Map<String, Object> segment(String text, Double noSpeechProb) {
        Map<String, Object> s = new HashMap<>();
        s.put("start", 0.0);
        s.put("end", 4.3);
        s.put("text", text);
        s.put("no_speech_prob", noSpeechProb);
        return s;
    }

    private Object reasonOf(Map<String, Object> segment) {
        return marker.mark(List.of(segment)).get(0).get(TranscriptSuspectMarker.FIELD);
    }

    @Test
    @DisplayName("TC-CR160-001: 소리 없음 확률이 기준 이상이고 글이 있으면 NO_SPEECH")
    void noSpeech_atOrAboveSetting() {
        assertEquals("NO_SPEECH", reasonOf(segment("감사합니다.", 0.829)));
        assertEquals("NO_SPEECH", reasonOf(segment("감사합니다.", 0.6)));
    }

    @Test
    @DisplayName("TC-CR160-002: 띄어쓰기, 문장부호, 영문 대소문자가 달라도 문구를 담으면 KNOWN_PHRASE(원문 45)")
    void knownPhrase_normalized() {
        assertEquals("KNOWN_PHRASE", reasonOf(segment("자막제공자", 0.495)));
        assertEquals("KNOWN_PHRASE", reasonOf(segment("시청해주셔서 감사합니다!", 0.1)));
        assertEquals("KNOWN_PHRASE", reasonOf(segment("mbc뉴스 이덕영입니다.", null)));
    }

    @Test
    @DisplayName("TC-CR160-003: 둘 다 맞으면 NO_SPEECH(원문 15)")
    void both_prefersNoSpeech() {
        assertEquals("NO_SPEECH", reasonOf(segment("자막 제공 및 광고를 포함하고 있습니다.", 0.764)));
    }

    @Test
    @DisplayName("TC-CR160-004: 말이 있는 녹음은 표시하지 않는다(원문 42, 44)")
    void normalSpeech_notMarked() {
        assertNull(reasonOf(segment("확인용 녹취입니다. 다음 주 화요일까지 견적서를 거래처에 보내주세요.", 0.016)));
        assertNull(reasonOf(segment("확인용 두번째 노출이 풍고센터 221-7에서", 0.512)));
    }

    @Test
    @DisplayName("TC-CR160-005: 설정이 없거나 비면 표시하지 않고 칸은 null 로 둔다")
    void noSettings_noMarks() {
        configure(null, List.of());
        var out = marker.mark(List.of(segment("자막제공자", 0.9)));
        assertTrue(out.get(0).containsKey(TranscriptSuspectMarker.FIELD));
        assertNull(out.get(0).get(TranscriptSuspectMarker.FIELD));

        configure("  ", List.of(" ", ""));
        assertNull(reasonOf(segment("자막제공자", 0.9)));
    }

    @Test
    @DisplayName("TC-CR160-006: 글 없는 구간은 소리 없음 확률이 높아도 표시하지 않는다")
    void emptyText_notMarked() {
        assertNull(reasonOf(segment("", 0.95)));
        assertNull(reasonOf(segment(" . ", 0.95)));
        assertNull(reasonOf(segment(null, 0.95)));
    }

    @Test
    @DisplayName("TC-CR160-007: 소리 없음 확률이 없으면(옛 사이드카) 문구로만 본다")
    void missingProb_phraseOnly() {
        assertNull(reasonOf(segment("감사합니다.", null)));
        assertEquals("KNOWN_PHRASE", reasonOf(segment("자막제공자", null)));
    }

    @Test
    @DisplayName("TC-CR160-008: 기준값이 수가 아니면 소리 없음 표시만 끄고 예외를 내지 않는다")
    void invalidSetting_disablesNoSpeechOnly() {
        configure("abc", List.of("자막 제공"));
        assertNull(reasonOf(segment("감사합니다.", 0.9)));
        assertEquals("KNOWN_PHRASE", reasonOf(segment("자막제공자", 0.9)));
    }

    @Test
    @DisplayName("TC-CR160-009: 받은 구간은 바꾸지 않고 사본에 다른 칸을 그대로 옮긴다")
    void input_notMutated() {
        Map<String, Object> original = Map.of("start", 0.0, "end", 4.3, "text", "자막제공자", "avg_logprob", -0.7);
        var out = marker.mark(List.of(original));
        assertFalse(original.containsKey(TranscriptSuspectMarker.FIELD));
        assertEquals(-0.7, out.get(0).get("avg_logprob"));
        assertEquals("자막제공자", out.get(0).get("text"));
        assertEquals("KNOWN_PHRASE", out.get(0).get(TranscriptSuspectMarker.FIELD));
    }

    @Test
    @DisplayName("TC-CR160-010: 구간이 없으면 설정을 읽지 않고 그대로 돌려준다")
    void emptySegments_passThrough() {
        assertEquals(List.of(), marker.mark(List.of()));
        assertNull(marker.mark(null));
        verifyNoInteractions(settings);
    }
}
