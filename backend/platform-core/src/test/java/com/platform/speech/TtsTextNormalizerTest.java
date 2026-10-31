package com.platform.speech;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-165: 날짜·시각을 읽는 말로 바꾸기(BIZ-137, TC-TTS-201 ~ 210, 224).
 */
class TtsTextNormalizerTest {

    @ParameterizedTest(name = "{0}")
    @DisplayName("TC-TTS-201 날짜 세 모양")
    @ValueSource(strings = {"2026-10-31", "2026.10.31", "2026/10/31"})
    void convertsThreeDateShapes(String date) {
        assertThat(TtsTextNormalizer.normalize("회의는 " + date + " 입니다"))
                .isEqualTo("회의는 2026년 10월 31일 입니다");
    }

    @Test
    @DisplayName("TC-TTS-202 앞의 0 을 뺀다")
    void dropsLeadingZeros() {
        assertThat(TtsTextNormalizer.normalize("2026-01-05")).isEqualTo("2026년 1월 5일");
    }

    @ParameterizedTest(name = "{0} = {1}")
    @DisplayName("TC-TTS-203 ~ 205 오전, 오후, 0시, 12시, 초")
    @CsvSource({
            "09:05, 오전 9시 5분",
            "9:05, 오전 9시 5분",
            "14:00, 오후 2시",
            "23:59, 오후 11시 59분",
            "00:30, 오전 0시 30분",
            "12:00, 오후 12시",
            "14:00:30, 오후 2시 0분 30초",
            "14:30:00, 오후 2시 30분",
    })
    void convertsTimes(String time, String spoken) {
        assertThat(TtsTextNormalizer.normalize(time)).isEqualTo(spoken);
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("TC-TTS-206 날짜와 시각을 같이 바꾼다")
    @ValueSource(strings = {"2026-10-31 14:00", "2026-10-31T14:00"})
    void convertsDateWithTime(String value) {
        assertThat(TtsTextNormalizer.normalize(value + "에 시작")).isEqualTo("2026년 10월 31일 오후 2시에 시작");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("TC-TTS-207 ~ 209 없는 날짜·시각, 숫자가 더 붙음, 비율, 전화번호, 두 자리 연도는 그대로")
    @ValueSource(strings = {
            "2026-13-01", "2026-02-30", "25:00", "14:60",
            "12026-10-31", "14:000",
            "16:9", "010-1234-5678", "26.10.31"})
    void keepsValuesThatAreNotDatesOrTimes(String value) {
        String text = "값 " + value + " 끝";
        assertThat(TtsTextNormalizer.normalize(text)).isEqualTo(text);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @DisplayName("TC-TTS-224 글쓴이가 붙인 오전, 오후는 한 번만 읽는다")
    @CsvSource({
            "오후 2:30, 오후 2시 30분",
            "오전 9:05, 오전 9시 5분",
            "오후2:30, 오후 2시 30분",
            "오후 14:30, 오후 2시 30분",
            "오후 12:00, 오후 12시",
            "2026-11-07 오후 2:30, 2026년 11월 7일 오후 2시 30분",
            "오후 3:00~5:00, 오후 3시~오후 5시",
            "오후 3:00 - 5:00, 오후 3시 - 오후 5시",
            "14:00~16:00, 오후 2시~오후 4시",
            "'오후 3:00, 9:00', '오후 3시, 오전 9시'",
    })
    void readsWriterMeridiemOnce(String text, String spoken) {
        assertThat(TtsTextNormalizer.normalize(text)).isEqualTo(spoken);
    }

    @Test
    @DisplayName("TC-TTS-224 오전에 13시 이후가 붙으면 어느 쪽인지 몰라 그대로 둔다")
    void keepsConflictingMeridiem() {
        assertThat(TtsTextNormalizer.normalize("값 오전 14:00 끝")).isEqualTo("값 오전 14:00 끝");
    }

    @Test
    @DisplayName("날짜는 맞고 시각이 없는 값이면 날짜만 바꾼다")
    void convertsOnlyDateWhenTimeIsInvalid() {
        assertThat(TtsTextNormalizer.normalize("2026-10-31 25:00")).isEqualTo("2026년 10월 31일 25:00");
    }

    @Test
    @DisplayName("TC-TTS-210 한 글에 여럿, 날짜와 시각 말고는 그대로")
    void convertsOnlyDatesAndTimesInBriefing() {
        String text = "오늘 2026-10-31 09:30 주간 회의에서 12억 4천만 원 매출과 8.5% 성장을 보고했습니다.\n"
                + "다음 회의는 2026.11.07 14:00, 참석 3건, 화면 비율 16:9 입니다.";

        assertThat(TtsTextNormalizer.normalize(text)).isEqualTo(
                "오늘 2026년 10월 31일 오전 9시 30분 주간 회의에서 12억 4천만 원 매출과 8.5% 성장을 보고했습니다.\n"
                        + "다음 회의는 2026년 11월 7일 오후 2시, 참석 3건, 화면 비율 16:9 입니다.");
    }

    @Test
    void keepsNullAndEmpty() {
        assertThat(TtsTextNormalizer.normalize(null)).isNull();
        assertThat(TtsTextNormalizer.normalize("")).isEmpty();
    }
}
