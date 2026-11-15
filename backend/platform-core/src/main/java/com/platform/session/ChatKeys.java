package com.platform.session;

import java.util.regex.Pattern;

/**
 * CR-194 BIZ-277: 붙이는 앱이 주는 화면 키와 함께 보기 키의 글자 규칙. aimbase 는 뜻을 해석하지 않고 글자 그대로
 * 같은지만 보므로, 주소나 로그에 섞여도 문제가 없는 글자만 받는다.
 */
public final class ChatKeys {

    public static final int KEY_MAX = 128;
    /** BIZ-278: 묻는 사람 이름 길이. */
    public static final int USER_NAME_MAX = 100;

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9:/\\-_.\\[\\]]{1," + KEY_MAX + "}");

    private ChatKeys() {}

    public static boolean isValid(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** 비었으면 null. 주지 않은 것과 같게 본다. */
    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
