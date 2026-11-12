package com.platform.session;

/**
 * CR-178: 사용자가 답을 멈췄을 때 답 글 앞에 붙는 표시. 쓰는 곳(오케스트레이터, 도구 루프)과 읽는 곳(이어보기)이
 * 이 상수 하나를 쓴다. 이어보기는 표시를 떼고 멈춘 답으로 알린다.
 */
public final class ChatStopMarker {

    public static final String PREFIX = "[중단됨] ";

    private ChatStopMarker() {}

    public static boolean isStopped(String text) {
        return text != null && text.startsWith(PREFIX.trim());
    }

    /** 표시를 뗀 글. 표시가 없으면 그대로. */
    public static String strip(String text) {
        if (!isStopped(text)) return text;
        return text.substring(PREFIX.trim().length()).stripLeading();
    }
}
