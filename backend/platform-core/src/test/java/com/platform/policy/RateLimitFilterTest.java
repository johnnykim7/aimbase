package com.platform.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-168 TC-RL-001 ~ 006: 실행기 연결 신호는 테넌트 분당 요청 한도에 세지 않는다(BIZ-141).
 * 칸은 다 쓴 상태로 두고, 한도 판정을 불렀는지와 다음 단계로 넘어갔는지를 본다.
 */
class RateLimitFilterTest {

    private static final String TENANT = "t1";
    private static final String AGENT_ID = "14cc68a6-ca20-4562-8632-3c73d25d5bad";

    private TokenBucketRateLimiter rateLimiter;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(TokenBucketRateLimiter.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        when(subscriptions.findByTenantId(anyString())).thenReturn(Optional.empty());
        when(rateLimiter.tryAcquire(anyString(), anyInt()))
                .thenReturn(TokenBucketRateLimiter.RateLimitResult.exceeded(60, 61, 30));
        filter = new RateLimitFilter(rateLimiter, subscriptions, new ObjectMapper());
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
    }

    @Test
    void tcRl002_registerIsNotCounted() throws Exception {
        var res = new MockHttpServletResponse();

        assertThat(run("POST", "/api/v1/agents/register", res)).isTrue();
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt());
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
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/v1/auth/login",
            "GET, /actuator/health",
    })
    void tcRl006_pathsOutsideTheLimitStayOutside(String method, String path) throws Exception {
        assertThat(run(method, path, new MockHttpServletResponse())).isTrue();
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt());
    }
}
