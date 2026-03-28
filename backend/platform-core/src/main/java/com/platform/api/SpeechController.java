package com.platform.api;

import com.platform.repository.ConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * PRD-127: TTS/STT API Proxy (CR-011).
 *
 * OpenAI TTS(text-to-speech) 및 STT(speech-to-text/Whisper) API를 프록시.
 * 테넌트의 OpenAI Connection 설정에서 API 키를 가져와 요청을 중계.
 */
@RestController
@RequestMapping("/api/v1/speech")
public class SpeechController {

    private static final Logger log = LoggerFactory.getLogger(SpeechController.class);
    private static final String OPENAI_TTS_URL = "https://api.openai.com/v1/audio/speech";
    private static final String OPENAI_STT_URL = "https://api.openai.com/v1/audio/transcriptions";

    private final ConnectionRepository connectionRepository;
    private final HttpClient httpClient;

    public SpeechController(ConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .build();
    }

    /**
     * POST /api/v1/speech/tts — Text-to-Speech.
     *
     * Request Body: { "text": "안녕하세요", "model": "tts-1", "voice": "alloy", "speed": 1.0 }
     * Response: audio/mpeg 바이너리 스트림
     */
    @PostMapping("/tts")
    public ResponseEntity<byte[]> textToSpeech(@RequestBody TtsRequest request) {
        String apiKey = resolveOpenAIKey();
        if (apiKey == null) {
            return ResponseEntity.status(503).body(null);
        }

        try {
            String model = request.model() != null ? request.model() : "tts-1";
            String voice = request.voice() != null ? request.voice() : "alloy";
            double speed = request.speed() != null ? request.speed() : 1.0;

            String jsonBody = String.format(
                    """
                    {"model":"%s","input":"%s","voice":"%s","speed":%s}
                    """,
                    model,
                    request.text().replace("\"", "\\\"").replace("\n", "\\n"),
                    voice,
                    speed
            );

            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_TTS_URL))
                    .timeout(java.time.Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<byte[]> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofByteArray());

            if (resp.statusCode() >= 400) {
                log.warn("TTS API error: HTTP {}", resp.statusCode());
                return ResponseEntity.status(resp.statusCode())
                        .body(resp.body());
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/mpeg"))
                    .body(resp.body());

        } catch (Exception e) {
            log.error("TTS proxy failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(null);
        }
    }

    /**
     * POST /api/v1/speech/stt — Speech-to-Text (Whisper).
     *
     * Request: multipart/form-data { file: audio blob, model: "whisper-1", language: "ko" }
     * Response: { "text": "인식된 텍스트" }
     */
    @PostMapping(value = "/stt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> speechToText(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "model", defaultValue = "whisper-1") String model,
            @RequestParam(value = "language", defaultValue = "ko") String language) {

        String apiKey = resolveOpenAIKey();
        if (apiKey == null) {
            return ResponseEntity.status(503)
                    .body(ApiResponse.error("OpenAI connection not configured"));
        }

        try {
            String boundary = "----AimbaseBoundary" + System.currentTimeMillis();
            byte[] fileBytes = file.getBytes();
            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "audio.wav";

            byte[] body = buildMultipartBody(boundary, model, language, filename, fileBytes);

            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_STT_URL))
                    .timeout(java.time.Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() >= 400) {
                log.warn("STT API error: HTTP {} — {}", resp.statusCode(), resp.body());
                return ResponseEntity.status(resp.statusCode())
                        .body(ApiResponse.error("STT failed: " + resp.body()));
            }

            // OpenAI 응답: {"text": "..."}
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> result = om.readValue(resp.body(), Map.class);

            return ResponseEntity.ok(ApiResponse.ok(result));

        } catch (Exception e) {
            log.error("STT proxy failed: {}", e.getMessage());
            return ResponseEntity.status(500)
                    .body(ApiResponse.error("STT proxy error: " + e.getMessage()));
        }
    }

    /**
     * 테넌트의 OpenAI Connection에서 API 키 조회.
     */
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

    private byte[] buildMultipartBody(String boundary, String model, String language,
                                        String filename, byte[] fileBytes) {
        StringBuilder sb = new StringBuilder();
        String crlf = "\r\n";

        // model field
        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"model\"").append(crlf).append(crlf);
        sb.append(model).append(crlf);

        // language field
        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"language\"").append(crlf).append(crlf);
        sb.append(language).append(crlf);

        // file field header
        sb.append("--").append(boundary).append(crlf);
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename).append("\"").append(crlf);
        sb.append("Content-Type: application/octet-stream").append(crlf).append(crlf);

        byte[] headerBytes = sb.toString().getBytes();
        byte[] footerBytes = (crlf + "--" + boundary + "--" + crlf).getBytes();

        byte[] result = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, result, headerBytes.length, fileBytes.length);
        System.arraycopy(footerBytes, 0, result, headerBytes.length + fileBytes.length, footerBytes.length);

        return result;
    }

    // ─── 요청 DTO ────────────────────────────────────────────────────

    public record TtsRequest(
            String text,
            String model,
            String voice,
            Double speed
    ) {}
}
