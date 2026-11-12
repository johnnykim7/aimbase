package com.platform.speech.voice;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * CR-186: 맥 받아쓰기 서버 {@code /stream} 웹소켓 한 발화(BIZ-253). 시험에서 가짜로 바꿀 수 있게 따로 둔다.
 */
public interface SttStreamClient {

    /** 받아쓰기 서버에서 오는 것. 한 스레드에서 차례로 부른다고 보지 않는다. */
    interface Listener {
        void onText(String json);

        void onClosed(int code, String reason);

        void onError(Throwable error);
    }

    interface Stream {
        /** 소리 조각을 보낸다. 넘긴 배열은 호출 뒤에 바뀌어도 된다. */
        void sendAudio(byte[] chunk);

        /** 발화 끝. 받아쓰기 서버는 빈 바이너리 프레임을 끝으로 보고 마지막 글을 보낸다. */
        void sendEnd();

        void close();
    }

    /**
     * @param baseUrl 연결 config 의 {@code base_url}(http 또는 https)
     * @param connectTimeout 받아쓰기 서버는 모델이 준비된 뒤에야 연결을 받으므로, 막 켜진 서버면 그만큼 기다린다
     */
    CompletableFuture<Stream> open(String baseUrl, String apiKey, String sessionId, Duration connectTimeout, Listener listener);

    /** {@code http://host:port/} 를 {@code ws://host:port/stream?api_key=..&session_id=..} 로 바꾼다. */
    static String streamUrl(String baseUrl, String apiKey, String sessionId) {
        String base = baseUrl.trim().replaceAll("/+$", "");
        if (base.startsWith("https://")) base = "wss://" + base.substring("https://".length());
        else if (base.startsWith("http://")) base = "ws://" + base.substring("http://".length());
        StringBuilder url = new StringBuilder(base).append("/stream");
        char sep = '?';
        if (apiKey != null && !apiKey.isBlank()) {
            url.append(sep).append("api_key=").append(encode(apiKey));
            sep = '&';
        }
        if (sessionId != null && !sessionId.isBlank()) {
            url.append(sep).append("session_id=").append(encode(sessionId));
        }
        return url.toString();
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
