package com.platform.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.ConnectionEntity;
import com.platform.llm.ConnectionAdapterFactory;
import com.platform.llm.ConnectionApiKeys;
import com.platform.repository.ConnectionRepository;
import com.platform.speech.SpeechException;
import com.platform.speech.SpeechService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    private final ConnectionRepository connectionRepository;
    private final SpeechService speechService;
    private final ObjectMapper objectMapper;
    private final URI openaiTtsUri;
    private final HttpClient httpClient;

    public SpeechController(ConnectionRepository connectionRepository,
                            SpeechService speechService,
                            ObjectMapper objectMapper,
                            @Value("${aimbase.speech.openai-tts-url}") String openaiTtsUrl) {
        this.connectionRepository = connectionRepository;
        this.speechService = speechService;
        this.objectMapper = objectMapper;
        this.openaiTtsUri = URI.create(openaiTtsUrl);
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
        if (request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().body(null);
        }
        String apiKey = resolveOpenAIKey();
        if (apiKey == null) {
            return ResponseEntity.status(503).body(null);
        }

        try {
            String jsonBody = buildTtsBody(request);

            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(openaiTtsUri)
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
     * CR-165: 글에 역슬래시, 탭, 캐리지 리턴 같은 제어 문자가 들어도 JSON 이 깨지지 않게 직렬화기로 만든다.
     */
    String buildTtsBody(TtsRequest request) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model() != null ? request.model() : "tts-1");
        body.put("input", request.text());
        body.put("voice", request.voice() != null ? request.voice() : "alloy");
        body.put("speed", request.speed() != null ? request.speed() : 1.0);
        return objectMapper.writeValueAsString(body);
    }

    /**
     * 테넌트의 OpenAI 연결에서 API 키 조회 (TTS 전용).
     *
     * <p>CR-165: 처음 만든 모양({@code type=openai}, {@code config.api_key})을 먼저 본다. 그 모양으로
     * 등록해 쓰는 곳이 있으면 그대로 돈다. 없으면 관리 화면과 연결 API 가 만드는 OpenAI LLM 연결을 본다.
     */
    private String resolveOpenAIKey() {
        List<ConnectionEntity> connected = connectionRepository.findAll().stream()
                .filter(c -> "connected".equalsIgnoreCase(c.getStatus()))
                .toList();

        String legacyKey = connected.stream()
                .filter(c -> "openai".equalsIgnoreCase(c.getType()))
                .findFirst()
                .map(c -> c.getConfig() != null ? (String) c.getConfig().get("api_key") : null)
                .orElse(null);
        if (legacyKey != null) {
            return legacyKey;
        }

        return connected.stream()
                .filter(c -> "llm".equalsIgnoreCase(c.getType())
                        && "openai".equals(ConnectionAdapterFactory.normalizeAdapterType(c.getAdapter())))
                .map(c -> ConnectionApiKeys.resolve(c.getConfig()))
                .filter(Objects::nonNull)
                .findFirst()
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
