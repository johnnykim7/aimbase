package com.platform.api;

import com.platform.repository.ConnectionRepository;
import com.platform.speech.SpeechException;
import com.platform.speech.SpeechService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PRD-127: TTS/STT API Proxy (CR-011).
 *
 * OpenAI TTS(text-to-speech) 및 STT(speech-to-text/Whisper) API를 프록시.
 * 테넌트의 OpenAI Connection 설정에서 API 키를 가져와 요청을 중계.
 *
 * CR-060: STT 로직은 {@link SpeechService} 로 이동. 본 컨트롤러는 Platform JWT 경로 유지.
 */
@RestController
@RequestMapping("/api/v1/speech")
public class SpeechController {

    private static final Logger log = LoggerFactory.getLogger(SpeechController.class);
    private static final String OPENAI_TTS_URL = "https://api.openai.com/v1/audio/speech";

    private final ConnectionRepository connectionRepository;
    private final SpeechService speechService;
    private final HttpClient httpClient;

    public SpeechController(ConnectionRepository connectionRepository, SpeechService speechService) {
        this.connectionRepository = connectionRepository;
        this.speechService = speechService;
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
     * CR-060: 실제 Whisper 호출은 {@link SpeechService} 로 위임. 본 엔드포인트는 Platform JWT 경로 유지.
     * Request: multipart/form-data { file: audio blob, model: "whisper-1", language: "ko" }
     * Response: { "text": "...", "language": "...", "duration": ... }
     */
    @PostMapping(value = "/stt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> speechToText(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "model", defaultValue = "whisper-1") String ignoredModel,
            @RequestParam(value = "language", defaultValue = "ko") String language) {

        try {
            byte[] audio = file.getBytes();
            String mimeType = file.getContentType();
            String filename = file.getOriginalFilename();

            SpeechService.TranscribeResult r = speechService.transcribe(audio, mimeType, filename, language);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", r.text());
            if (r.language() != null) body.put("language", r.language());
            if (r.durationSec() != null) body.put("duration", r.durationSec());
            return ResponseEntity.ok(ApiResponse.ok(body));

        } catch (SpeechException e) {
            return switch (e.getCode()) {
                case PROVIDER_UNAVAILABLE -> ResponseEntity.status(503)
                        .body(ApiResponse.error(e.getMessage()));
                case TIMEOUT -> ResponseEntity.status(504)
                        .body(ApiResponse.error(e.getMessage()));
                case UPSTREAM_ERROR -> ResponseEntity.status(502)
                        .body(ApiResponse.error(e.getMessage()));
            };
        } catch (java.io.IOException e) {
            log.error("STT request read failed: {}", e.getMessage());
            return ResponseEntity.status(400).body(ApiResponse.error("invalid multipart: " + e.getMessage()));
        }
    }

    /**
     * 테넌트의 OpenAI Connection에서 API 키 조회 (TTS 전용).
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

    // ─── 요청 DTO ────────────────────────────────────────────────────

    public record TtsRequest(
            String text,
            String model,
            String voice,
            Double speed
    ) {}
}
