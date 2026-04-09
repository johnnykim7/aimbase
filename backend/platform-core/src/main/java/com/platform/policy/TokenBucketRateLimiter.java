package com.platform.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * CR-013: Redis 기반 분산 TokenBucket Rate Limiter.
 *
 * 슬라이딩 윈도우 카운터 방식:
 * - 키: rl:{tenantId}:{minuteWindow}
 * - Redis INCR + EXPIRE 원자적 실행 (Lua 스크립트)
 * - 분산 환경(멀티 인스턴스)에서 정확한 카운팅 보장
 */
@Component
public class TokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);
    private static final String KEY_PREFIX = "rl:";
    private static final int WINDOW_SECONDS = 60;

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Lua 스크립트: INCR + TTL 설정을 원자적으로 수행.
     * 키가 없으면 생성 후 1로 설정 + TTL, 있으면 INCR만 수행.
     * 반환값: 현재 카운트.
     */
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
        "local current = redis.call('INCR', KEYS[1])\n" +
        "if current == 1 then\n" +
        "  redis.call('EXPIRE', KEYS[1], ARGV[1])\n" +
        "end\n" +
        "return current",
        Long.class
    );

    public TokenBucketRateLimiter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Rate Limit 체크 및 카운트 증가.
     *
     * @param tenantId 테넌트 ID
     * @param maxRequests 분당 최대 요청 수 (0 = 무제한)
     * @return 체크 결과
     */
    public RateLimitResult tryAcquire(String tenantId, int maxRequests) {
        if (maxRequests <= 0) {
            return RateLimitResult.allowed(0, 0);
        }

        String key = buildKey(tenantId);

        try {
            Long currentCount = redisTemplate.execute(
                INCREMENT_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(WINDOW_SECONDS)
            );

            if (currentCount == null) {
                log.warn("Redis rate limit script returned null for tenant: {}", tenantId);
                return RateLimitResult.allowed(maxRequests, 0);
            }

            long remaining = Math.max(0, maxRequests - currentCount);
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            int retryAfter = ttl != null && ttl > 0 ? ttl.intValue() : WINDOW_SECONDS;

            if (currentCount > maxRequests) {
                log.warn("Rate limit exceeded for tenant: {} ({}/{})", tenantId, currentCount, maxRequests);
                return RateLimitResult.exceeded(maxRequests, currentCount, retryAfter);
            }

            return RateLimitResult.allowed(maxRequests, remaining);

        } catch (Exception e) {
            log.error("Redis rate limit check failed for tenant: {}, allowing request", tenantId, e);
            return RateLimitResult.allowed(maxRequests, maxRequests);
        }
    }

    private String buildKey(String tenantId) {
        long minuteWindow = System.currentTimeMillis() / (WINDOW_SECONDS * 1000L);
        return KEY_PREFIX + tenantId + ":" + minuteWindow;
    }

    /**
     * Rate Limit 체크 결과.
     */
    public record RateLimitResult(
        boolean allowed,
        int limit,
        long remaining,
        long current,
        int retryAfterSeconds
    ) {
        public static RateLimitResult allowed(int limit, long remaining) {
            return new RateLimitResult(true, limit, remaining, 0, 0);
        }

        public static RateLimitResult exceeded(int limit, long current, int retryAfterSeconds) {
            return new RateLimitResult(false, limit, 0, current, retryAfterSeconds);
        }
    }
}
