package com.platform.speech;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CR-165: 음성 합성 전에 글의 날짜와 시각을 읽는 말로 바꾼다(BIZ-137).
 *
 * <p>복제 목소리 엔진은 {@code 2026-10-31} 을 숫자 하나씩 읽거나 {@code 14:00} 을 "십사 공공" 으로 읽는다.
 * 금액, 백분율 같은 다른 숫자는 엔진이 맞게 읽어 건드리지 않는다.
 *
 * <p>날짜는 네 자리 연도만 본다. 두 자리 연도({@code 26.10.31})는 소수나 판번호와 가릴 수 없다.
 * 시각은 분이 두 자리인 것만 본다. {@code 16:9} 같은 비율을 시각으로 읽지 않기 위해서다.
 */
public final class TtsTextNormalizer {

    private static final String TIME = "(\\d{1,2}):(\\d{2})(?::(\\d{2}))?";

    /** 날짜 뒤에 공백이나 T 로 시각이 붙으면 같이 바꾼다. 구분자는 세 칸이 같아야 한다. */
    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)(\\d{4})([-./])(\\d{1,2})\\2(\\d{1,2})(?:[ T]" + TIME + ")?(?!\\d)");

    private static final Pattern STANDALONE_TIME = Pattern.compile(
            "(?<!\\d)(?<!\\d:)" + TIME + "(?!\\d)(?!:\\d)");

    private TtsTextNormalizer() {}

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return text;
        return replaceTimes(replaceDates(text));
    }

    private static String replaceDates(String text) {
        Matcher m = DATE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String date = spokenDate(m.group(1), m.group(3), m.group(4));
            String replacement;
            if (date == null) {
                replacement = m.group();
            } else if (m.group(5) == null) {
                replacement = date;
            } else {
                String time = spokenTime(m.group(5), m.group(6), m.group(7));
                // 시각이 없는 값이면 날짜만 바꾸고 뒤의 글자는 그대로 둔다.
                replacement = time != null
                        ? date + " " + time
                        : date + m.group().substring(m.end(4) - m.start());
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String replaceTimes(String text) {
        Matcher m = STANDALONE_TIME.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String time = spokenTime(m.group(1), m.group(2), m.group(3));
            m.appendReplacement(out, Matcher.quoteReplacement(time != null ? time : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String spokenDate(String year, String month, String day) {
        int y = Integer.parseInt(year);
        int mo = Integer.parseInt(month);
        int d = Integer.parseInt(day);
        try {
            LocalDate.of(y, mo, d);
        } catch (DateTimeException e) {
            return null;
        }
        return y + "년 " + mo + "월 " + d + "일";
    }

    private static String spokenTime(String hour, String minute, String second) {
        int h = Integer.parseInt(hour);
        int mi = Integer.parseInt(minute);
        int s = second != null ? Integer.parseInt(second) : 0;
        if (h > 23 || mi > 59 || s > 59) return null;

        StringBuilder sb = new StringBuilder();
        if (h < 12) {
            sb.append("오전 ").append(h).append("시");
        } else {
            sb.append("오후 ").append(h == 12 ? 12 : h - 12).append("시");
        }
        if (mi > 0 || s > 0) sb.append(' ').append(mi).append("분");
        if (s > 0) sb.append(' ').append(s).append("초");
        return sb.toString();
    }
}
