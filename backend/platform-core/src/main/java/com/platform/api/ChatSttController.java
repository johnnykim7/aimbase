package com.platform.api;

import com.platform.config.PlatformSettingsService;
import com.platform.policy.AuditLogger;
import com.platform.speech.AudioMimeValidator;
import com.platform.speech.SpeechException;
import com.platform.speech.SpeechService;
import com.platform.speech.SttRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-060 위젯 전용 STT 엔드포인트 ({@code POST /api/v1/chat/stt}).
 *
 * Scope={@code chat:stt} 를 가진 위젯 토큰 또는 Platform JWT 로 호출 가능.
 * 서비스 로직은 {@link SpeechService} 에 위임하고 본 컨트롤러는 검증·감사·Rate Limit 담당.
 * 세션 ID 는 요청 파라미터로 수신한다(위젯 JWT 에는 session_id claim 이 없음).
 */
@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Chat STT", description = "CR-060 위젯 음성 입력 (Whisper)")
public class ChatSttController {

    private final SpeechService speechService;
    private final SttRateLimiter rateLimiter;
    private final AudioMimeValidator mimeValidator;
    private final PlatformSettingsService settings;
    private final AuditLogger auditLogger;

    public ChatSttController(SpeechService speechService,
                             SttRateLimiter rateLimiter,
                             AudioMimeValidator mimeValidator,
                             PlatformSettingsService settings,
                             AuditLogger auditLogger) {
        this.speechService = speechService;
        this.rateLimiter = rateLimiter;
        this.mimeValidator = mimeValidator;
        this.settings = settings;
        this.auditLogger = auditLogger;
    }

    @PostMapping(value = "/stt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_chat:stt') or isAuthenticated()")
    @Operation(summary = "위젯 음성 입력 → Whisper 변환 (녹음 후 일괄 전송)")
    public ResponseEntity<ApiResponse<?>> transcribe(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "session_id", required = false) String sessionIdParam) {

        String sessionId = (sessionIdParam != null && !sessionIdParam.isBlank())
                ? sessionIdParam : "anonymous";

        // BIZ-104 — 세션당 분당 호출 한도
        try {
            rateLimiter.checkAndIncrement(sessionId);
        } catch (SttRateLimiter.SttRateLimitExceededException e) {
            return ResponseEntity.status(429)
                    .body(ApiResponse.error("STT_RATE_LIMITED: " + e.getMessage()));
        }

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("STT_FILE_MISSING: file is required"));
        }

        // BIZ-103 — 파일 크기 상한
        long maxBytes = settings.getLong("widget.stt.max-size-bytes", 26_214_400L);
        if (file.getSize() > maxBytes) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("STT_FILE_TOO_LARGE: " + file.getSize() + " > " + maxBytes));
        }

        byte[] audio;
        try {
            audio = file.getBytes();
        } catch (java.io.IOException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("STT_INVALID_MULTIPART: " + e.getMessage()));
        }

        // magic number MIME 판정 + 화이트리스트
        String mimeType;
        try {
            mimeType = mimeValidator.detect(audio);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("STT_INVALID_MIME: " + e.getMessage()));
        }

        List<String> allowed = settings.getStringList(
                "widget.stt.allowed-mime-types",
                List.of("audio/webm", "audio/mp4", "audio/mpeg", "audio/wav", "audio/ogg"));
        if (!mimeValidator.isAllowed(mimeType, allowed)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("STT_INVALID_MIME: not in allowed list: " + mimeType));
        }

        String lang = (language != null && !language.isBlank())
                ? language
                : settings.getString("widget.stt.default-language", "auto");

        // Whisper 호출
        SpeechService.TranscribeResult r;
        try {
            r = speechService.transcribe(audio, mimeType, file.getOriginalFilename(), lang);
        } catch (SpeechException e) {
            return switch (e.getCode()) {
                case PROVIDER_UNAVAILABLE -> ResponseEntity.status(503)
                        .body(ApiResponse.error("STT_PROVIDER_UNAVAILABLE: " + e.getMessage()));
                case TIMEOUT -> ResponseEntity.status(504)
                        .body(ApiResponse.error("STT_TIMEOUT: " + e.getMessage()));
                case UPSTREAM_ERROR -> ResponseEntity.status(502)
                        .body(ApiResponse.error("STT_UPSTREAM_ERROR: " + e.getMessage()));
            };
        }

        // BIZ-102 — Whisper 응답 duration 기준 녹음 시간 사후 검증
        long maxDuration = settings.getLong("widget.stt.max-duration-seconds", 60L);
        if (r.durationSec() != null && r.durationSec() > maxDuration) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(
                            "STT_FILE_TOO_LONG: " + r.durationSec() + "s > " + maxDuration + "s"));
        }

        // 감사 로그 — 변환 텍스트 본문은 저장 금지(PII)
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("duration_sec", r.durationSec());
        detail.put("size_bytes", file.getSize());
        detail.put("language", r.language());
        detail.put("mime_type", mimeType);
        auditLogger.log("stt_transcribe", "whisper", null, sessionId, detail, null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", r.text());
        body.put("language", r.language() != null ? r.language() : lang);
        if (r.durationSec() != null) body.put("duration_sec", r.durationSec());
        return ResponseEntity.ok(ApiResponse.ok(body));
    }
}
