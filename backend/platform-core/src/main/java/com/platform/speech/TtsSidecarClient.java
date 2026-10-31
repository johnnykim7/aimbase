package com.platform.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-166: 같은 목소리 음성 생성기(tts-sidecar) 호출.
 *
 * <p>HTTP/1.1 로 고정한다. 기본값(HTTP/2 업그레이드 시도)으로 uvicorn 을 부르면 본문이 끊기는 일이 있었다(CR-133).
 */
@Component
public class TtsSidecarClient {

    /** 생성기가 꺼져 있거나 모델을 올리는 중. */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    /** 생성기가 시간 안에 답하지 않음. */
    public static class TimeoutException extends RuntimeException {
        public TimeoutException(Throwable cause) { super("tts generator timeout", cause); }
    }

    /** 생성기가 만들지 못했다고 답함. code 는 생성기가 준 오류 코드. */
    public static class FailedException extends RuntimeException {
        private final String code;
        public FailedException(String code) { super(code); this.code = code; }
        public String getCode() { return code; }
    }

    public record Synthesis(byte[] audio, String mimeType, Double durationSec, Double elapsedSec, Integer attempt) {}

    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TtsSidecarClient(@Value("${aimbase.tts.sidecar-url}") String baseUrl,
                            @Value("${aimbase.tts.request-timeout-seconds:600}") long requestTimeoutSeconds,
                            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** 생성기에 올라온 음색 이름. 생성기가 준비되지 않았으면 {@link UnavailableException}. */
    public List<String> voices() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                .timeout(HEALTH_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UnavailableException("tts generator unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("interrupted", e);
        }
        if (resp.statusCode() != 200) {
            throw new UnavailableException("tts generator health " + resp.statusCode(), null);
        }
        try {
            List<String> names = new ArrayList<>();
            objectMapper.readTree(resp.body()).path("voices").forEach(v -> names.add(v.asText()));
            return names;
        } catch (IOException e) {
            throw new UnavailableException("tts generator health body: " + e.getMessage(), e);
        }
    }

    public Synthesis synthesize(String voice, String text) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("voice", voice);
        body.put("text", text);
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(baseUrl + "/synthesize"))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body)))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        HttpResponse<byte[]> resp;
        try {
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new TimeoutException(e);
        } catch (IOException e) {
            throw new UnavailableException("tts generator unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("interrupted", e);
        }

        int status = resp.statusCode();
        if (status == 200) {
            return new Synthesis(resp.body(),
                    resp.headers().firstValue("Content-Type").orElse("audio/mpeg"),
                    header(resp, "X-Duration-Sec"),
                    header(resp, "X-Elapsed-Sec"),
                    resp.headers().firstValue("X-Attempt").map(Integer::valueOf).orElse(null));
        }
        if (status == 503) {
            throw new UnavailableException("tts generator not ready", null);
        }
        throw new FailedException(errorCode(resp.body(), status));
    }

    private String errorCode(byte[] body, int status) {
        try {
            JsonNode node = objectMapper.readTree(body);
            String code = node.path("error").asText("");
            if (!code.isBlank()) {
                return code;
            }
        } catch (IOException ignored) {
            // 본문이 JSON 이 아니면 상태 코드로 남긴다.
        }
        return "generator_http_" + status;
    }

    private static Double header(HttpResponse<?> resp, String name) {
        return resp.headers().firstValue(name).map(Double::valueOf).orElse(null);
    }
}
