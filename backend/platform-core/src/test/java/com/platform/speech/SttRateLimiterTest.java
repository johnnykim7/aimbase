package com.platform.speech;

import com.platform.config.PlatformSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * CR-060 SttRateLimiter — BIZ-104 세션당 분당 호출 한도 단위 검증.
 */
@ExtendWith(MockitoExtension.class)
class SttRateLimiterTest {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private PlatformSettingsService settings;

    @Test
    void passesWhenUnderLimit() {
        when(settings.getInt("widget.stt.rate-limit-per-minute", 10)).thenReturn(10);
        when(redisTemplate.<Long>execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<List<String>>any(), any()))
                .thenReturn(5L);
        SttRateLimiter limiter = new SttRateLimiter(redisTemplate, settings);

        assertThatCode(() -> limiter.checkAndIncrement("sess-1")).doesNotThrowAnyException();
    }

    @Test
    void throwsWhenOverLimit() {
        when(settings.getInt("widget.stt.rate-limit-per-minute", 10)).thenReturn(10);
        when(redisTemplate.<Long>execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<List<String>>any(), any()))
                .thenReturn(11L);
        SttRateLimiter limiter = new SttRateLimiter(redisTemplate, settings);

        assertThatThrownBy(() -> limiter.checkAndIncrement("sess-1"))
                .isInstanceOf(SttRateLimiter.SttRateLimitExceededException.class)
                .hasMessageContaining("limit=10");
    }

    @Test
    void failsOpenOnRedisError() {
        when(settings.getInt("widget.stt.rate-limit-per-minute", 10)).thenReturn(10);
        when(redisTemplate.<Long>execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<List<String>>any(), any()))
                .thenThrow(new RuntimeException("redis down"));
        SttRateLimiter limiter = new SttRateLimiter(redisTemplate, settings);

        // Redis 장애 시 가용성 우선으로 예외 없이 통과해야 함
        assertThatCode(() -> limiter.checkAndIncrement("sess-1")).doesNotThrowAnyException();
    }

    @Test
    void usesAnonymousWhenSessionIdMissing() {
        when(settings.getInt("widget.stt.rate-limit-per-minute", 10)).thenReturn(10);
        when(redisTemplate.<Long>execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<List<String>>any(), any()))
                .thenReturn(1L);
        SttRateLimiter limiter = new SttRateLimiter(redisTemplate, settings);

        assertThatCode(() -> limiter.checkAndIncrement(null)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkAndIncrement("")).doesNotThrowAnyException();
    }
}
