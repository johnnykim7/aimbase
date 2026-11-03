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
    private static final String AM = "오전";
    private static final String PM = "오후";

    /** 날짜 뒤에 공백이나 T 로 시각이 붙으면 같이 바꾼다. 구분자는 세 칸이 같아야 한다. */
    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)(\\d{4})([-./])(\\d{1,2})\\2(\\d{1,2})(?:[ T]" + TIME + ")?(?!\\d)");

    /** 글쓴이가 앞에 붙인 오전, 오후도 같이 잡는다. 그대로 두면 "오후 오후 2시" 로 두 번 읽힌다. */
    private static final Pattern STANDALONE_TIME = Pattern.compile(
            "(?:(" + AM + "|" + PM + ")\\s*)?(?<!\\d)(?<!\\d:)" + TIME + "(?!\\d)(?!:\\d)");

    /** "오후 3:00~5:00" 의 뒤 시각은 앞의 오전, 오후를 잇는다. */
    private static final Pattern RANGE_GAP = Pattern.compile("\\s*[~～\\-]\\s*");

    private static final String SPOKEN_TIME = "(?:" + AM + "|" + PM + ") \\d{1,2}시(?: \\d{1,2}분)?(?: \\d{1,2}초)?";
    private static final String SPOKEN_DATE = "\\d{4}년 \\d{1,2}월 \\d{1,2}일(?: " + SPOKEN_TIME + ")?";
    private static final String SPOKEN = "(" + SPOKEN_DATE + "|" + SPOKEN_TIME + ")";

    /** 엔진이 물결과 붙임표를 읽지 않아 "오후 3시 오후 5시" 로 들린다. 바꾼 말 둘 사이면 "부터, 까지" 로 읽는다. */
    private static final Pattern SPOKEN_RANGE = Pattern.compile(SPOKEN + "\\s*[~～\\-]\\s*" + SPOKEN + "(까지)?");

    private TtsTextNormalizer() {}

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return text;
        return readRanges(replaceTimes(replaceDates(text)));
    }

    private static String readRanges(String text) {
        return SPOKEN_RANGE.matcher(text).replaceAll("$1부터 $2까지");
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
                String time = spokenTime(null, m.group(5), m.group(6), m.group(7));
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
        String lastMeridiem = null;
        int lastEnd = -1;
        while (m.find()) {
            String meridiem = m.group(1);
            if (meridiem == null && lastMeridiem != null
                    && RANGE_GAP.matcher(text.substring(lastEnd, m.start())).matches()) {
                meridiem = lastMeridiem;
            }
            String time = spokenTime(meridiem, m.group(2), m.group(3), m.group(4));
            m.appendReplacement(out, Matcher.quoteReplacement(time != null ? time : m.group()));
            lastMeridiem = time != null ? meridiem : null;
            lastEnd = m.end();
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

    /** meridiem 은 글쓴이가 붙인 오전, 오후. 없으면 24시 표기로 보고 정한다. */
    private static String spokenTime(String meridiem, String hour, String minute, String second) {
        int h = Integer.parseInt(hour);
        int mi = Integer.parseInt(minute);
        int s = second != null ? Integer.parseInt(second) : 0;
        if (h > 23 || mi > 59 || s > 59) return null;
        // "오전 14:00" 은 어느 쪽이 맞는지 알 수 없어 글을 그대로 둔다.
        if (AM.equals(meridiem) && h > 12) return null;

        StringBuilder sb = new StringBuilder();
        if (meridiem != null) {
            sb.append(meridiem).append(' ').append(h > 12 ? h - 12 : h).append("시");
        } else if (h < 12) {
            sb.append(AM).append(' ').append(h).append("시");
        } else {
            sb.append(PM).append(' ').append(h == 12 ? 12 : h - 12).append("시");
        }
        if (mi > 0 || s > 0) sb.append(' ').append(mi).append("분");
        if (s > 0) sb.append(' ').append(s).append("초");
        return sb.toString();
    }
}
