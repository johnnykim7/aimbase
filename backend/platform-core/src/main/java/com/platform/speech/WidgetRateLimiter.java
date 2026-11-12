package com.platform.speech;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * CR-186: 위젯 읽어 주기의 세션별 분당 요청 수 제한(BIZ-251).
 *
 * <p>{@link SttRateLimiter} 와 같은 Redis INCR + 60초 만료 방식이다. 키에 테넌트를 넣어 다른 테넌트의
 * 같은 세션 이름과 섞이지 않게 한다. Redis 가 죽으면 막지 않고 통과시킨다(읽기가 멈추는 것보다 낫다).
 */
@Component
public class WidgetRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(WidgetRateLimiter.class);
    private static final String WINDOW_SECONDS = "60";

    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1])\n" +
            "if current == 1 then\n" +
            "  redis.call('EXPIRE', KEYS[1], ARGV[1])\n" +
            "end\n" +
            "return current",
            Long.class
    );

    private final RedisTemplate<String, String> redisTemplate;

    public WidgetRateLimiter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** @return 이번 요청을 받아도 되면 true */
    public boolean tryAcquire(String key, int limitPerMinute) {
        try {
            Long current = redisTemplate.execute(INCREMENT_SCRIPT, Collections.singletonList(key), WINDOW_SECONDS);
            return current == null || current <= limitPerMinute;
        } catch (Exception e) {
            log.warn("[CR-186] 요청 수 제한을 Redis 에서 세지 못해 통과시킨다: {}", e.getMessage());
            return true;
        }
    }
}
