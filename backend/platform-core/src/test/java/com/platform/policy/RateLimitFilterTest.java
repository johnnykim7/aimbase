package com.platform.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.PlatformSettingsService;
import com.platform.repository.master.SubscriptionRepository;
import com.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-168 TC-RL-001 ~ 008: 실행기 연결 신호는 테넌트 분당 요청 한도에 세지 않고 전용 칸으로 따로 센다(BIZ-141).
 * 테넌트 칸은 다 쓴 상태로 두고, 어느 칸으로 판정했는지와 다음 단계로 넘어갔는지를 본다.
 */
class RateLimitFilterTest {

    private static final String TENANT = "t1";
    private static final String AGENT_ID = "14cc68a6-ca20-4562-8632-3c73d25d5bad";

    private static final String BUCKET = RateLimitFilter.AGENT_SIGNAL_BUCKET;

    private TokenBucketRateLimiter rateLimiter;
    private PlatformSettingsService settings;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(TokenBucketRateLimiter.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        when(subscriptions.findByTenantId(anyString())).thenReturn(Optional.empty());
        when(rateLimiter.tryAcquire(anyString(), anyInt()))
                .thenReturn(TokenBucketRateLimiter.RateLimitResult.exceeded(60, 61, 30));
        when(rateLimiter.tryAcquire(eq(BUCKET), anyString(), anyInt()))
                .thenReturn(TokenBucketRateLimiter.RateLimitResult.allowed(60, 59));
        settings = mock(PlatformSettingsService.class);
        when(settings.getInt(eq(RateLimitFilter.AGENT_SIGNAL_LIMIT_KEY), anyInt())).thenReturn(60);
        filter = new RateLimitFilter(rateLimiter, subscriptions, settings, new ObjectMapper());
        TenantContext.setTenantId(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 다음 단계로 넘어갔으면 true. */
    private boolean run(String method, String path, MockHttpServletResponse res) throws Exception {
        var chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest(method, path), res, chain);
        return chain.getRequest() != null;
    }

    @Test
    void tcRl001_heartbeatIsNotCounted() throws Exception {
        var res = new MockHttpServletResponse();

        assertThat(run("POST", "/api/v1/agents/" + AGENT_ID + "/heartbeat", res)).isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("X-RateLimit-Limit")).isNull();
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt());
        verify(rateLimiter).tryAcquire(BUCKET, TENANT, 60);
    }

    @Test
    void tcRl002_registerIsNotCounted() throws Exception {
        var res = new MockHttpServletResponse();

        assertThat(run("POST", "/api/v1/agents/register", res)).isTrue();
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt());
        verify(rateLimiter).tryAcquire(BUCKET, TENANT, 60);
    }

    @Test
    void tcRl003_businessRequestIsRejectedWhenLimitIsUsed() throws Exception {
        var res = new MockHttpServletResponse();

        assertThat(run("POST", "/api/v1/workflows/wf-1/run", res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("30");
        verify(rateLimiter).tryAcquire(TENANT, 60);
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /api/v1/agents",
            "DELETE, /api/v1/agents/" + AGENT_ID,
            "GET, /api/v1/agents/" + AGENT_ID + "/heartbeat",
    })
    void tcRl004_otherAgentRequestsAreCounted(String method, String path) throws Exception {
        assertThat(run(method, path, new MockHttpServletResponse())).isFalse();
        verify(rateLimiter).tryAcquire(TENANT, 60);
        verify(rateLimiter, never()).tryAcquire(anyString(), anyString(), anyInt());
    }

    @ParameterizedTest
    @CsvSource({
            "/api/v1/agents/abc/heartbeat",
            "/api/v1/agents/" + AGENT_ID + "/heartbeat/x",
            "/api/v1/agents/register/x",
    })
    void tcRl005_lookAlikePathsAreCounted(String path) throws Exception {
        assertThat(run("POST", path, new MockHttpServletResponse())).isFalse();
        verify(rateLimiter).tryAcquire(TENANT, 60);
        verify(rateLimiter, never()).tryAcquire(anyString(), anyString(), anyInt());
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/v1/auth/login",
            "GET, /actuator/health",
    })
    void tcRl006_pathsOutsideTheLimitStayOutside(String method, String path) throws Exception {
        assertThat(run(method, path, new MockHttpServletResponse())).isTrue();
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt());
        verify(rateLimiter, never()).tryAcquire(anyString(), anyString(), anyInt());
    }

    @Test
    void tcRl007_connectionSignalOverItsOwnLimitIsRejected() throws Exception {
        when(rateLimiter.tryAcquire(eq(BUCKET), anyString(), anyInt()))
                .thenReturn(TokenBucketRateLimiter.RateLimitResult.exceeded(60, 61, 12));
        var res = new MockHttpServletResponse();

        assertThat(run("POST", "/api/v1/agents/" + AGENT_ID + "/heartbeat", res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("12");
        assertThat(res.getHeader("X-RateLimit-Limit")).isNull();
        assertThat(res.getContentAsString()).contains("Agent connection signal limit exceeded");
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt());
    }

    @Test
    void tcRl008_connectionSignalLimitComesFromSetting() throws Exception {
        when(settings.getInt(eq(RateLimitFilter.AGENT_SIGNAL_LIMIT_KEY), anyInt())).thenReturn(5);

        assertThat(run("POST", "/api/v1/agents/register", new MockHttpServletResponse())).isTrue();
        verify(rateLimiter).tryAcquire(BUCKET, TENANT, 5);
    }
}
