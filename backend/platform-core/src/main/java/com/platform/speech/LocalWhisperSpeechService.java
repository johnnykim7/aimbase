package com.platform.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.ConnectionEntity;
import com.platform.repository.ConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-133: 로컬 Whisper 사이드카(맥 MLX) 호출 클라이언트.
 *
 * <p>{@link SpeechService} 를 <b>구현하지 않는다</b>. 그 인터페이스는 위젯 STT(CR-060)용이며
 * 구현체가 둘이 되면 {@code SpeechController}/{@code ChatSttController} 주입이 모호해진다.
 * 회의녹음 배치는 세그먼트·소요시간까지 필요해 반환 타입도 다르다.
 *
 * <p>사이드카는 공유기 포트포워딩으로 인터넷에 노출되므로 {@code X-Api-Key} 를 반드시 보낸다.
 * 엔드포인트·키는 {@code stt_local} 타입 커넥션의 config 에서 읽는다.
 */
@Service
public class LocalWhisperSpeechService {

    private static final Logger log = LoggerFactory.getLogger(LocalWhisperSpeechService.class);

    /** 커넥션 type 값. FE/운영에서 이 값으로 STT 사이드카 커넥션을 등록한다. */
    public static final String CONNECTION_TYPE = "stt_local";

    private final ConnectionRepository connectionRepository;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LocalWhisperSpeechService(ConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                // HTTP/1.1 고정 필수. 기본값(HTTP_2)이면 Java 가 h2c 업그레이드를 시도하고,
                // 사이드카 uvicorn(h11)은 HTTP/1.1 전용이라 업그레이드를 거부하면서
                // Content-Length 헤더만 남고 본문이 0바이트로 유실된다 → FastAPI 가 422(file 누락).
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /** 전사 결과. 위젯용 {@code TranscribeResult} 와 달리 세그먼트·소요시간을 포함한다. */
    public record BatchTranscribeResult(
            String text,
            String language,
            Double durationSec,
            Double elapsedSec,
            List<Map<String, Object>> segments) {}

    /**
     * 사이드카에 오디오를 보내 전사한다. 회의녹음은 수 분이 걸리므로 호출자는 비동기 경로에서 부른다.
     *
     * @param connectionId 사용할 커넥션 id. null 이면 활성 {@code stt_local} 커넥션 중 첫 번째.
     */
    public BatchTranscribeResult transcribe(byte[] audio, String mimeType, String filename,
                                            String language, String connectionId) {
        ConnectionEntity conn = resolveConnection(connectionId);
        Map<String, Object> cfg = conn.getConfig() != null ? conn.getConfig() : Map.of();

        String baseUrl = str(cfg.get("base_url"));
        if (baseUrl == null) {
            throw new SpeechException(SpeechException.ErrorCode.PROVIDER_UNAVAILABLE,
                    "STT 커넥션에 base_url 이 없다: " + conn.getId(), null);
        }
        String apiKey = str(cfg.get("api_key"));
        // 사이드카는 fail-closed 로 키를 요구한다. 없으면 호출해봐야 401 이므로 미리 막는다.
        if (apiKey == null) {
            throw new SpeechException(SpeechException.ErrorCode.PROVIDER_UNAVAILABLE,
                    "STT 커넥션에 api_key 가 없다: " + conn.getId(), null);
        }

        long timeoutSec = longOr(cfg.get("timeout_seconds"), 1800L);
        String url = baseUrl.replaceAll("/+$", "") + "/transcribe";

        String safeFilename = (filename != null && !filename.isBlank()) ? filename : "audio.wav";
        String lang = (language != null && !language.isBlank()) ? language : "auto";

        String boundary = "----AimbaseTranscribe" + System.nanoTime();
        byte[] body = buildMultipartBody(boundary, lang, safeFilename, audio, mimeType);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("X-Api-Key", apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        log.info("[CR-133] 사이드카 전사 요청: {} ({} bytes, lang={}, timeout={}s)",
                safeFilename, audio.length, lang, timeoutSec);

        HttpResponse<String> resp;
        try {
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw SpeechException.timeout(e);
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new SpeechException(SpeechException.ErrorCode.UPSTREAM_ERROR,
                    "사이드카 호출 실패: " + e.getMessage(), e);
        }

        if (resp.statusCode() >= 400) {
            log.warn("[CR-133] 사이드카 오류: HTTP {} — {}", resp.statusCode(), truncate(resp.body()));
            throw SpeechException.upstream(resp.statusCode(), resp.body());
        }
        return parse(resp.body(), lang);
    }

    private ConnectionEntity resolveConnection(String connectionId) {
        if (connectionId != null && !connectionId.isBlank()) {
            return connectionRepository.findById(connectionId)
                    .orElseThrow(() -> new SpeechException(SpeechException.ErrorCode.PROVIDER_UNAVAILABLE,
                            "STT 커넥션을 찾을 수 없다: " + connectionId, null));
        }
        return connectionRepository.findAll().stream()
                .filter(c -> CONNECTION_TYPE.equalsIgnoreCase(c.getType()))
                .filter(c -> !"disabled".equalsIgnoreCase(String.valueOf(c.getStatus())))
                .findFirst()
                .orElseThrow(() -> new SpeechException(SpeechException.ErrorCode.PROVIDER_UNAVAILABLE,
                        "활성 " + CONNECTION_TYPE + " 커넥션이 없다", null));
    }

    private BatchTranscribeResult parse(String json, String requestedLanguage) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<Map<String, Object>> segments = new ArrayList<>();
            for (JsonNode s : root.path("segments")) {
                Map<String, Object> seg = new LinkedHashMap<>();
                seg.put("start", s.path("start").asDouble());
                seg.put("end", s.path("end").asDouble());
                seg.put("text", s.path("text").asText(""));
                segments.add(seg);
            }
            return new BatchTranscribeResult(
                    root.path("text").asText(""),
                    root.hasNonNull("language") ? root.get("language").asText() : requestedLanguage,
                    root.hasNonNull("duration") ? root.get("duration").asDouble() : null,
                    root.hasNonNull("elapsed") ? root.get("elapsed").asDouble() : null,
                    segments);
        } catch (Exception e) {
            throw new SpeechException(SpeechException.ErrorCode.UPSTREAM_ERROR,
                    "사이드카 응답 파싱 실패: " + e.getMessage(), e);
        }
    }

    private static byte[] buildMultipartBody(String boundary, String language, String filename,
                                             byte[] fileBytes, String mimeType) {
        String crlf = "\r\n";
        StringBuilder sb = new StringBuilder();

        if (language != null && !"auto".equalsIgnoreCase(language)) {
            sb.append("--").append(boundary).append(crlf);
            sb.append("Content-Disposition: form-data; name=\"language\"").append(crlf).append(crlf);
            sb.append(language).append(crlf);
        }

        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"").append(crlf);
        sb.append("Content-Type: ").append(mimeType != null ? mimeType : "application/octet-stream")
                .append(crlf).append(crlf);

        byte[] headerBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = (crlf + "--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                headerBytes.length + fileBytes.length + footerBytes.length);
        out.writeBytes(headerBytes);
        out.writeBytes(fileBytes);
        out.writeBytes(footerBytes);
        return out.toByteArray();
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
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
