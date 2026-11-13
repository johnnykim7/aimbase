package com.platform.speech.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ConnectionEntity;
import com.platform.session.ChatRejectedException;
import com.platform.speech.SttLocalConnections;
import com.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CR-186: 음성 대화 입장권(PRD-582, BIZ-253).
 *
 * <p>브라우저 웹소켓은 Authorization 머리글을 붙일 수 없어 위젯 토큰으로 중계를 열 수 없다. 토큰을 주소에 싣지 않으려고
 * 토큰으로 인증된 요청이 짧게 사는 입장권을 받고, 웹소켓은 그 입장권 하나로만 연다. 입장권은 꺼내는 순간 지운다.
 */
@Service
public class VoiceTicketService {

    private static final Logger log = LoggerFactory.getLogger(VoiceTicketService.class);

    public static final String KEY_ENABLED = "widget.voice.enabled";
    public static final String KEY_TICKET_TTL = "widget.voice.ticket-ttl-seconds";
    public static final String KEY_MAX_CONCURRENT = "widget.voice.max-concurrent-streams";
    /** 아래 셋은 위젯이 쓰는 값이라 위젯 설정 조회로만 내보낸다. */
    public static final String KEY_END_SILENCE_MS = "widget.voice.end-silence-ms";
    public static final String KEY_SPEECH_LEVEL = "widget.voice.speech-level";
    public static final String KEY_IDLE_END_SECONDS = "widget.voice.idle-end-seconds";

    public static final String STREAM_PATH = "/api/v1/chat/voice/stream";
    static final String TICKET_PREFIX = "vt_";
    private static final String REDIS_PREFIX = "voice:ticket:";

    /**
     * 꺼내기와 지우기를 한 번에 해서 같은 입장권으로 두 번 열지 못하게 한다.
     * GETDEL 은 Redis 6.2 부터라 개발기 59 의 5.0.7 에서 오류가 났다. 스크립트는 판을 가리지 않는다.
     */
    private static final DefaultRedisScript<String> TAKE_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1])\n" +
            "if v then redis.call('DEL', KEYS[1]) end\n" +
            "return v",
            String.class);

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;
    private final PlatformSettingsService settings;
    private final SttLocalConnections sttConnections;
    private final VoiceRelayRegistry registry;
    private final SecureRandom random = new SecureRandom();

    public VoiceTicketService(RedisTemplate<String, String> redisTemplate,
                              ObjectMapper objectMapper,
                              PlatformSettingsService settings,
                              SttLocalConnections sttConnections,
                              VoiceRelayRegistry registry) {
        this.redis = redisTemplate;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.sttConnections = sttConnections;
        this.registry = registry;
    }

    public boolean enabled() {
        return !"false".equalsIgnoreCase(settings.getString(KEY_ENABLED, "true"));
    }

    public int maxConcurrent() {
        return settings.getInt(KEY_MAX_CONCURRENT, 3);
    }

    /**
     * @throws ChatRejectedException {@code voice_invalid:session_id}(400), {@code voice_disabled}(409),
     *                               {@code voice_unavailable}(503), {@code voice_session_busy}(409), {@code voice_busy}(429)
     */
    public Map<String, Object> issue(String sessionId, String userRef, String origin) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ChatRejectedException(HttpStatus.BAD_REQUEST, "voice_invalid:session_id");
        }
        if (!enabled()) {
            throw new ChatRejectedException(HttpStatus.CONFLICT, "voice_disabled");
        }
        ConnectionEntity stt = sttConnections.findActive()
                .orElseThrow(() -> new ChatRejectedException(HttpStatus.SERVICE_UNAVAILABLE, "voice_unavailable"));
        String tenantId = TenantContext.getTenantId();
        if (registry.isOpen(VoiceRelayRegistry.key(tenantId, sessionId))) {
            throw new ChatRejectedException(HttpStatus.CONFLICT, "voice_session_busy");
        }
        if (registry.openCount() >= maxConcurrent()) {
            throw new ChatRejectedException(HttpStatus.TOO_MANY_REQUESTS, "voice_busy");
        }

        long ttl = Math.max(1L, settings.getLong(KEY_TICKET_TTL, 30L));
        String ticket = TICKET_PREFIX + newToken();
        VoiceTicket content = new VoiceTicket(tenantId, sessionId, userRef, blankToNull(origin), stt.getId());
        try {
            redis.opsForValue().set(REDIS_PREFIX + ticket, objectMapper.writeValueAsString(content), Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.warn("[CR-186] 입장권을 Redis 에 두지 못했다: {}", e.getMessage());
            throw new ChatRejectedException(HttpStatus.SERVICE_UNAVAILABLE, "voice_unavailable");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket", ticket);
        body.put("expires_in_seconds", ttl);
        body.put("stream_path", STREAM_PATH);
        return body;
    }

    /** 입장권을 꺼내고 지운다. 없거나 지났거나 이미 썼으면 비어 있다. */
    public Optional<VoiceTicket> consume(String ticket) {
        if (ticket == null || !ticket.startsWith(TICKET_PREFIX)) return Optional.empty();
        try {
            String raw = redis.execute(TAKE_SCRIPT, List.of(REDIS_PREFIX + ticket));
            if (raw == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(raw, VoiceTicket.class));
        } catch (Exception e) {
            log.warn("[CR-186] 입장권을 읽지 못했다: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
