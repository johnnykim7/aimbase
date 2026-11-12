package com.platform.speech;

import com.platform.config.PlatformSettingsService;
import com.platform.policy.AuditLogger;
import com.platform.session.ChatRejectedException;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CR-186: 위젯 답 읽어 주기(PRD-580). 위젯이 만든 읽을 글 한 조각을 소리로 바꾼다.
 *
 * <p>맥 음성 엔진은 위젯 경로에서만 짧게 기다리고 넘으면 OpenAI 로 넘긴다(BIZ-252). 사람이 단추를 누르고
 * 기다리는데 연결 기본값(600초)까지 기다리면 읽기가 시작되지 않기 때문이다.
 */
@Service
public class WidgetTtsService {

    private static final Logger log = LoggerFactory.getLogger(WidgetTtsService.class);

    public static final String KEY_ENABLED = "widget.tts.enabled";
    public static final String KEY_VOICE = "widget.tts.voice";
    public static final String KEY_SPEED = "widget.tts.speed";
    public static final String KEY_MAX_CHARS = "widget.tts.max-chars";
    public static final String KEY_RATE_LIMIT = "widget.tts.rate-limit-per-minute";
    public static final String KEY_LOCAL_TIMEOUT = "widget.tts.local-timeout-seconds";

    static final String AUDIT_ACTION = "WIDGET_TTS";
    private static final String RATE_KEY_PREFIX = "tts:rate:";

    private final TtsServiceRouter router;
    private final TtsVoiceCatalog catalog;
    private final WidgetRateLimiter rateLimiter;
    private final PlatformSettingsService settings;
    private final AuditLogger auditLogger;

    public WidgetTtsService(TtsServiceRouter router,
                            TtsVoiceCatalog catalog,
                            WidgetRateLimiter rateLimiter,
                            PlatformSettingsService settings,
                            AuditLogger auditLogger) {
        this.router = router;
        this.catalog = catalog;
        this.rateLimiter = rateLimiter;
        this.settings = settings;
        this.auditLogger = auditLogger;
    }

    public boolean enabled() {
        return !"false".equalsIgnoreCase(settings.getString(KEY_ENABLED, "true"));
    }

    public int maxChars() {
        return settings.getInt(KEY_MAX_CHARS, 300);
    }

    /**
     * @throws ChatRejectedException {@code tts_disabled}(409), {@code tts_invalid:text}(400),
     *                               {@code tts_rate_limited}(429), {@code tts_unavailable}(503), {@code tts_upstream_error}(502)
     */
    public TtsServiceRouter.Result synthesize(String sessionId, String text, String userId) {
        if (!enabled()) {
            throw new ChatRejectedException(HttpStatus.CONFLICT, "tts_disabled");
        }
        if (text == null || text.isBlank() || text.length() > maxChars()) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "tts_invalid:text");
        }
        String session = sessionId != null && !sessionId.isBlank() ? sessionId : "anonymous";
        int limit = settings.getInt(KEY_RATE_LIMIT, 60);
        if (!rateLimiter.tryAcquire(RATE_KEY_PREFIX + TenantContext.getTenantId() + ":" + session, limit)) {
            throw new ChatRejectedException(HttpStatus.TOO_MANY_REQUESTS, "tts_rate_limited");
        }

        Duration localTimeout = Duration.ofSeconds(Math.max(1L, settings.getLong(KEY_LOCAL_TIMEOUT, 20L)));
        long started = System.nanoTime();
        TtsServiceRouter.Result r;
        try {
            r = router.synthesize(text, null, voice(), settings.getDouble(KEY_SPEED, 1.0), localTimeout);
        } catch (SpeechException e) {
            log.warn("[CR-186] 위젯 읽기 실패({}): {}", e.getCode(), e.getMessage());
            if (e.getCode() == SpeechException.ErrorCode.PROVIDER_UNAVAILABLE) {
                throw new ChatRejectedException(HttpStatus.SERVICE_UNAVAILABLE, "tts_unavailable");
            }
            throw new ChatRejectedException(HttpStatus.BAD_GATEWAY, "tts_upstream_error");
        }
        if (r.status() >= 400) {
            log.warn("[CR-186] OpenAI 음성 합성 오류 {}", r.status());
            throw new ChatRejectedException(HttpStatus.BAD_GATEWAY, "tts_upstream_error");
        }

        // 읽은 글은 남기지 않는다(답 본문이 감사 로그로 새지 않게).
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("chars", text.length());
        detail.put("engine", r.engine());
        detail.put("elapsed_ms", (System.nanoTime() - started) / 1_000_000L);
        auditLogger.log(AUDIT_ACTION, "widget_tts", userId, session, detail, null);
        return r;
    }

    /** 설정 목소리, 비었으면 목소리 목록의 기본 목소리. 목록도 비었으면 null(OpenAI 기본 목소리). */
    String voice() {
        String configured = settings.getString(KEY_VOICE, "");
        if (configured != null && !configured.isBlank()) return configured.trim();
        return catalog.voices().stream()
                .filter(TtsVoiceCatalog.Voice::isDefault)
                .findFirst()
                .or(() -> catalog.voices().stream().findFirst())
                .map(TtsVoiceCatalog.Voice::id)
                .orElse(null);
    }
}
