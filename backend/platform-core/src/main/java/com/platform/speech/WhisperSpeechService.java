package com.platform.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;

/**
 * CR-060: Whisper(OpenAI) 기반 STT 구현.
 *
 * 기존 {@link com.platform.api.SpeechController#speechToText} 로직을 추출·일반화했다.
 * 응답 포맷은 {@code verbose_json} 을 요청해 {@code duration} 필드로 사후 녹음 시간 검증이 가능하다.
 */
@Service
public class WhisperSpeechService implements SpeechService {

    private static final Logger log = LoggerFactory.getLogger(WhisperSpeechService.class);
    private static final String WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions";
    private static final String DEFAULT_MODEL = "whisper-1";

    private final ConnectionRepository connectionRepository;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WhisperSpeechService(ConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public TranscribeResult transcribe(byte[] audio, String mimeType, String filename, String language) {
        String apiKey = resolveOpenAIKey();
        if (apiKey == null) {
            throw SpeechException.providerUnavailable();
        }

        String safeFilename = (filename != null && !filename.isBlank()) ? filename : defaultFilename(mimeType);
        String lang = (language != null && !language.isBlank()) ? language : "auto";

        String boundary = "----AimbaseBoundary" + System.currentTimeMillis();
        byte[] body = buildMultipartBody(boundary, DEFAULT_MODEL, lang, safeFilename, audio, mimeType);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(WHISPER_URL))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> resp;
        try {
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw SpeechException.timeout(e);
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new SpeechException(SpeechException.ErrorCode.UPSTREAM_ERROR,
                    "Whisper call failed: " + e.getMessage(), e);
        }

        if (resp.statusCode() >= 400) {
            log.warn("Whisper API error: HTTP {} — {}", resp.statusCode(), resp.body());
            throw SpeechException.upstream(resp.statusCode(), resp.body());
        }

        return parseVerboseJson(resp.body(), lang);
    }

    private TranscribeResult parseVerboseJson(String json, String requestedLanguage) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String text = root.path("text").asText("");
            String detectedLang = root.hasNonNull("language")
                    ? root.get("language").asText(requestedLanguage)
                    : requestedLanguage;
            Double duration = root.hasNonNull("duration") ? root.get("duration").asDouble() : null;
            return new TranscribeResult(text, detectedLang, duration);
        } catch (Exception e) {
            throw new SpeechException(SpeechException.ErrorCode.UPSTREAM_ERROR,
                    "Invalid Whisper response JSON: " + e.getMessage(), e);
        }
    }

    private String resolveOpenAIKey() {
        return connectionRepository.findAll().stream()
                .filter(c -> "openai".equalsIgnoreCase(c.getType())
                        && "connected".equalsIgnoreCase(c.getStatus()))
                .findFirst()
                .map(c -> {
                    Map<String, Object> cfg = c.getConfig();
                    return cfg != null ? (String) cfg.get("api_key") : null;
                })
                .orElse(null);
    }

    private static String defaultFilename(String mimeType) {
        if (mimeType == null) return "audio.bin";
        return switch (mimeType.toLowerCase()) {
            case "audio/webm" -> "audio.webm";
            case "audio/mp4" -> "audio.mp4";
            case "audio/mpeg" -> "audio.mp3";
            case "audio/wav" -> "audio.wav";
            case "audio/ogg" -> "audio.ogg";
            default -> "audio.bin";
        };
    }

    private static byte[] buildMultipartBody(String boundary, String model, String language,
                                             String filename, byte[] fileBytes, String mimeType) {
        String crlf = "\r\n";
        StringBuilder sb = new StringBuilder();

        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"model\"").append(crlf).append(crlf);
        sb.append(model).append(crlf);

        if (language != null && !"auto".equalsIgnoreCase(language)) {
            sb.append("--").append(boundary).append(crlf);
            sb.append("Content-Disposition: form-data; name=\"language\"").append(crlf).append(crlf);
            sb.append(language).append(crlf);
        }

        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"response_format\"").append(crlf).append(crlf);
        sb.append("verbose_json").append(crlf);

        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"").append(crlf);
        sb.append("Content-Type: ").append(mimeType != null ? mimeType : "application/octet-stream")
                .append(crlf).append(crlf);

        byte[] headerBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = (crlf + "--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream(headerBytes.length + fileBytes.length + footerBytes.length);
        out.writeBytes(headerBytes);
        out.writeBytes(fileBytes);
        out.writeBytes(footerBytes);
        return out.toByteArray();
    }
}
