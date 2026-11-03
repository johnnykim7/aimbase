package com.platform.api;

import com.platform.speech.SpeechException;
import com.platform.speech.SpeechService;
import com.platform.speech.TtsServiceRouter;
import com.platform.speech.TtsVoiceCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PRD-127: TTS/STT API Proxy (CR-011).
 *
 * OpenAI TTS(text-to-speech) 및 STT(speech-to-text/Whisper) API를 프록시.
 * 테넌트의 OpenAI Connection 설정에서 API 키를 가져와 요청을 중계.
 *
 * CR-060: STT 로직은 {@link SpeechService} 로 이동. 본 컨트롤러는 Platform JWT 경로 유지.
 * CR-165: TTS 길 나누기와 OpenAI 호출은 {@link TtsServiceRouter} 로 이동. 목소리 목록 창구 추가.
 */
@RestController
@RequestMapping("/api/v1/speech")
public class SpeechController {

    private static final Logger log = LoggerFactory.getLogger(SpeechController.class);

    /** 소리를 만든 엔진(local, openai). 맥 엔진이 실패해 OpenAI 로 전환됐는지 소비앱이 본다. */
    static final String ENGINE_HEADER = "X-Aimbase-TTS-Engine";

    private final SpeechService speechService;
    private final TtsServiceRouter ttsRouter;
    private final TtsVoiceCatalog voiceCatalog;

    public SpeechController(SpeechService speechService,
                            TtsServiceRouter ttsRouter,
                            TtsVoiceCatalog voiceCatalog) {
        this.speechService = speechService;
        this.ttsRouter = ttsRouter;
        this.voiceCatalog = voiceCatalog;
    }

    /**
     * POST /api/v1/speech/tts — Text-to-Speech.
     *
     * Request Body: { "text": "안녕하세요", "model": "tts-1", "voice": "alloy", "speed": 1.0 }
     * Response: audio/mpeg 바이너리 스트림, 머리글 X-Aimbase-TTS-Engine
     *
     * <p>CR-165: 목소리 목록에 있는 목소리는 맥 음성 엔진으로 먼저 보낸다. 목록 밖 목소리는 지금처럼 OpenAI.
     */
    @PostMapping("/tts")
    public ResponseEntity<byte[]> textToSpeech(@RequestBody TtsRequest request) {
        if (request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().body(null);
        }
        // 목록 밖 목소리의 speed 는 지금처럼 OpenAI 가 판단한다.
        if (voiceCatalog.find(request.voice()).isPresent() && !voiceCatalog.speedInRange(request.speed())) {
            return ResponseEntity.badRequest().body(null);
        }

        try {
            TtsServiceRouter.Result r = ttsRouter.synthesize(
                    request.text(), request.model(), request.voice(), request.speed());
            if (r.status() >= 400) {
                return ResponseEntity.status(r.status()).body(r.body());
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/mpeg"))
                    .header(ENGINE_HEADER, r.engine())
                    .body(r.body());
        } catch (SpeechException e) {
            if (e.getCode() == SpeechException.ErrorCode.PROVIDER_UNAVAILABLE) {
                log.warn("TTS unavailable: {}", e.getMessage());
                return ResponseEntity.status(503).body(null);
            }
            log.error("TTS proxy failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(null);
        }
    }

    /**
     * GET /api/v1/speech/voices: 음성 합성 목소리 목록(CR-165, PRD-359).
     *
     * <p>소비앱 선택 화면용이다. 맥 엔진을 부르지 않으므로 맥이 꺼져 있어도 나온다.
     * OpenAI 전환용 목소리({@code openai_voice})는 내보내지 않는다.
     */
    @GetMapping("/voices")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listVoices() {
        List<Map<String, Object>> voices = voiceCatalog.voices().stream()
                .map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", v.id());
                    m.put("name", v.name());
                    m.put("description", v.description());
                    m.put("default", v.isDefault());
                    return m;
                })
                .toList();

        Map<String, Object> speed = new LinkedHashMap<>();
        speed.put("min", voiceCatalog.speedMin());
        speed.put("max", voiceCatalog.speedMax());
        speed.put("default", TtsVoiceCatalog.NORMAL_SPEED);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("voices", voices);
        body.put("speed", speed);
        return ResponseEntity.ok(ApiResponse.ok(body));
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

    // ─── 요청 DTO ────────────────────────────────────────────────────

    public record TtsRequest(
            String text,
            String model,
            String voice,
            Double speed
    ) {}
}
