package com.platform.speech;

import com.platform.config.PlatformSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * CR-060 BIZ-104 — 세션당 분당 STT 호출 제한 (Redis INCR + TTL 60s).
 *
 * {@link com.platform.policy.TokenBucketRateLimiter} 와 동일한 Lua 원자 스크립트 패턴이지만
 * 키 스페이스를 {@code stt:rate:{sessionId}} 로 분리해 Platform 전역 rate limit 와 간섭하지 않는다.
 * 한도는 {@code widget.stt.rate-limit-per-minute} 설정에서 읽는다.
 */
@Component
public class SttRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SttRateLimiter.class);
    private static final String KEY_PREFIX = "stt:rate:";
    private static final int WINDOW_SECONDS = 60;

    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1])\n" +
            "if current == 1 then\n" +
            "  redis.call('EXPIRE', KEYS[1], ARGV[1])\n" +
            "end\n" +
            "return current",
            Long.class
    );

    private final RedisTemplate<String, String> redisTemplate;
    private final PlatformSettingsService settings;

    public SttRateLimiter(RedisTemplate<String, String> redisTemplate,
                          PlatformSettingsService settings) {
        this.redisTemplate = redisTemplate;
        this.settings = settings;
    }

    /**
     * 호출 카운트를 1 증가시키고 한도 초과 시 예외.
     *
     * @param sessionId widget 세션 식별자(JWT claim 또는 파라미터)
     * @throws SttRateLimitExceededException 한도 초과 (컨트롤러에서 429 매핑)
     */
    public void checkAndIncrement(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "anonymous";
        }
        int limit = settings.getInt("widget.stt.rate-limit-per-minute", 10);
        String key = KEY_PREFIX + sessionId;

        Long current;
        try {
            current = redisTemplate.execute(
                    INCREMENT_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(WINDOW_SECONDS)
            );
        } catch (Exception e) {
            // Redis 장애 시 열린 모드(fail-open) — 로그만 남기고 통과.
            // 보안 경계보다 가용성을 우선한다. 외부 메트릭/알람에서 별도 감지.
            log.warn("STT rate limiter Redis failure, failing open: {}", e.getMessage());
            return;
        }

        if (current != null && current > limit) {
            throw new SttRateLimitExceededException(limit, current);
        }
    }

    public static class SttRateLimitExceededException extends RuntimeException {
        private final int limit;
        private final long current;

        public SttRateLimitExceededException(int limit, long current) {
            super("STT rate limit exceeded (limit=" + limit + "/min, current=" + current + ")");
            this.limit = limit;
            this.current = current;
        }

        public int getLimit() { return limit; }
        public long getCurrent() { return current; }
    }
}
