package com.platform.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.ConnectionEntity;
import com.platform.llm.ConnectionApiKeys;
import com.platform.repository.ConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CR-165: 맥 음성 엔진(tts-mac-sidecar) 호출 클라이언트(BIZ-138).
 *
 * <p>받아쓰기 엔진({@link LocalWhisperSpeechService})과 같이 공유기 포트 전달로 인터넷에 열리므로
 * {@code X-Api-Key} 를 반드시 보낸다. 주소와 키는 {@code tts_local} 연결의 config 에서 읽고,
 * 키는 값 대신 서버 환경변수 이름({@code apiKey_env})으로 둘 수 있다.
 *
 * <p>엔진은 한 번에 하나만 만들고 1초에 약 8자를 만든다. 긴 글은 앞 요청을 기다리는 시간까지
 * 걸리므로 시간 제한 기본값을 길게 둔다.
 */
@Service
public class LocalTtsService {

    private static final Logger log = LoggerFactory.getLogger(LocalTtsService.class);

    /** 연결 type 값. 운영에서 이 값으로 맥 음성 엔진 연결을 등록한다. */
    public static final String CONNECTION_TYPE = "tts_local";

    private static final long DEFAULT_TIMEOUT_SECONDS = 600L;

    private final ConnectionRepository connectionRepository;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LocalTtsService(ConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                // 엔진 uvicorn(h11)은 HTTP/1.1 전용이다. h2c 업그레이드를 시도하면 본문이 유실된다(CR-133).
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * @return MP3 소리
     * @throws SpeechException 연결이 없거나, 닿지 않거나, 시간이 넘었거나, 엔진이 오류를 돌려줄 때
     */
    public byte[] synthesize(String text, String voice, double speed) {
        return synthesize(text, voice, speed, null);
    }

    /**
     * @param timeoutOverride null 이면 연결의 {@code timeout_seconds}. 위젯 읽기(CR-186, BIZ-252)는 사람이 기다리므로
     *                        짧게 끊고 OpenAI 로 넘긴다. 브리핑처럼 긴 글은 연결 값을 그대로 쓴다.
     */
    public byte[] synthesize(String text, String voice, double speed, Duration timeoutOverride) {
        ConnectionEntity conn = connectionRepository.findAll().stream()
                .filter(c -> CONNECTION_TYPE.equalsIgnoreCase(c.getType()))
                .filter(c -> !"disabled".equalsIgnoreCase(String.valueOf(c.getStatus())))
                .findFirst()
                .orElseThrow(() -> new SpeechException(SpeechException.ErrorCode.PROVIDER_UNAVAILABLE,
                        "활성 " + CONNECTION_TYPE + " 연결이 없다"));
        Map<String, Object> cfg = conn.getConfig() != null ? conn.getConfig() : Map.of();

        String baseUrl = str(cfg.get("base_url"));
        if (baseUrl == null) {
            throw new SpeechException(SpeechException.ErrorCode.PROVIDER_UNAVAILABLE,
                    "음성 합성 연결에 base_url 이 없다: " + conn.getId());
        }
        String apiKey = ConnectionApiKeys.resolve(cfg);
        // 엔진은 키가 없으면 401 이므로 부르기 전에 막는다.
        if (apiKey == null) {
            throw new SpeechException(SpeechException.ErrorCode.PROVIDER_UNAVAILABLE,
                    "음성 합성 연결에서 키를 찾지 못했다: " + conn.getId());
        }
        long timeoutSec = timeoutOverride != null
                ? Math.max(1L, timeoutOverride.toSeconds())
                : longOr(cfg.get("timeout_seconds"), DEFAULT_TIMEOUT_SECONDS);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("voice", voice);
        body.put("speed", speed);

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/synthesize"))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("X-Api-Key", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw new SpeechException(SpeechException.ErrorCode.PROVIDER_UNAVAILABLE,
                    "음성 합성 요청을 만들지 못했다: " + e.getMessage(), e);
        }

        log.info("[CR-165] 맥 음성 엔진 요청: voice={}, {}자, speed={}, timeout={}s",
                voice, text.length(), speed, timeoutSec);

        HttpResponse<byte[]> resp;
        try {
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.net.http.HttpTimeoutException e) {
            throw SpeechException.timeout(e);
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // 연결 거부(ConnectException)는 메시지가 비어 있어 종류 이름으로 원인을 남긴다.
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new SpeechException(SpeechException.ErrorCode.UPSTREAM_ERROR,
                    "맥 음성 엔진 호출 실패: " + reason, e);
        }

        if (resp.statusCode() >= 400) {
            String detail = truncate(new String(resp.body(), StandardCharsets.UTF_8));
            throw SpeechException.upstream(resp.statusCode(), detail);
        }
        return resp.body();
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static long longOr(Object v, long fallback) {
        if (v instanceof Number n) return n.longValue();
        try {
            return v != null ? Long.parseLong(String.valueOf(v).trim()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
