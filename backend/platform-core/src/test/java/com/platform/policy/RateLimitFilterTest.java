package com.platform.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.auth.UserPrincipal;
import com.platform.config.PlatformSettingsService;
import com.platform.session.ChatOwner;
import com.platform.domain.master.SubscriptionEntity;
import com.platform.repository.master.SubscriptionRepository;
import com.platform.tenant.TenantContext;
import com.platform.tenant.plan.SubscriptionPlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
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
 * CR-205 TC-RL-009 ~ 014: 위젯 토큰 요청(채팅)은 채팅 칸으로 따로 센다(BIZ-297).
 */
class RateLimitFilterTest {

    private static final String TENANT = "t1";
    private static final String AGENT_ID = "14cc68a6-ca20-4562-8632-3c73d25d5bad";

    private static final String BUCKET = RateLimitFilter.AGENT_SIGNAL_BUCKET;
    private static final String CHAT = RateLimitFilter.CHAT_BUCKET;

    private TokenBucketRateLimiter rateLimiter;
    private PlatformSettingsService settings;
    private RateLimitFilter filter;
    private SubscriptionRepository subscriptions;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(TokenBucketRateLimiter.class);
        subscriptions = mock(SubscriptionRepository.class);
        when(subscriptions.findByTenantId(anyString())).thenReturn(Optional.empty());
        when(rateLimiter.tryAcquire(anyString(), anyInt()))
                .thenReturn(TokenBucketRateLimiter.RateLimitResult.exceeded(60, 61, 30));
        when(rateLimiter.tryAcquire(eq(BUCKET), anyString(), anyInt()))
                .thenReturn(TokenBucketRateLimiter.RateLimitResult.allowed(60, 59));
        when(rateLimiter.tryAcquire(eq(CHAT), anyString(), anyInt()))
                .thenReturn(TokenBucketRateLimiter.RateLimitResult.allowed(300, 299));
        settings = mock(PlatformSettingsService.class);
        when(settings.getInt(eq(RateLimitFilter.AGENT_SIGNAL_LIMIT_KEY), anyInt())).thenReturn(60);
        filter = new RateLimitFilter(rateLimiter, subscriptions, settings, new SubscriptionPlanService(settings),
                new ObjectMapper());
        TenantContext.setTenantId(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    /** 인증 필터가 끝난 뒤의 상태. 위젯 토큰은 역할 WIDGET, 로그인 토큰은 그 사람의 역할이다. */
    private static void authenticatedAs(String role) {
        UserPrincipal principal = new UserPrincipal("u1", "u1", TENANT, role, Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static SubscriptionEntity subscriptionWithLimits(int apiLimit, int chatLimit) {
        SubscriptionEntity sub = subscriptionWithLimit(apiLimit);
        sub.setChatRpmLimit(chatLimit);
        return sub;
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

    /** CR-169 TC-SUB-009: 구독 변경 뒤 재기동 없이 다음 요청부터 새 한도로 센다(BIZ-142). */
    @Test
    void tcSub009_limitIsReadOnEveryRequest() throws Exception {
        when(subscriptions.findByTenantId(TENANT))
                .thenReturn(Optional.of(subscriptionWithLimit(20)))
                .thenReturn(Optional.of(subscriptionWithLimit(600)));

        run("GET", "/api/v1/workflows", new MockHttpServletResponse());
        verify(rateLimiter).tryAcquire(TENANT, 20);

        run("GET", "/api/v1/workflows", new MockHttpServletResponse());
        verify(rateLimiter).tryAcquire(TENANT, 600);
    }

    private static SubscriptionEntity subscriptionWithLimit(int limit) {
        SubscriptionEntity sub = new SubscriptionEntity();
        sub.setApiRpmLimit(limit);
        return sub;
    }

    /** CR-191 TC-PLAN-010: 구독 행이 없는 테넌트는 코드의 60 이 아니라 기본 플랜의 한도로 센다(BIZ-153). */
    @Test
    void tcPlan010_noSubscriptionUsesDefaultPlanLimit() throws Exception {
        when(settings.getString("plan.default", null)).thenReturn("free");
        when(settings.getString("plan.free.api-rpm-limit", null)).thenReturn("30");

        run("GET", "/api/v1/workflows", new MockHttpServletResponse());

        verify(rateLimiter).tryAcquire(TENANT, 30);
    }

    /** CR-205 TC-RL-009: 위젯 토큰 요청은 테넌트 칸을 다 썼어도 채팅 칸으로 넘어간다. */
    @Test
    void tcRl009_widgetRequestUsesChatBucket() throws Exception {
        when(subscriptions.findByTenantId(TENANT)).thenReturn(Optional.of(subscriptionWithLimits(60, 300)));
        authenticatedAs(ChatOwner.WIDGET_ROLE);
        var res = new MockHttpServletResponse();

        assertThat(run("POST", "/api/v1/chat/completions", res)).isTrue();
        assertThat(res.getHeader(RateLimitFilter.SCOPE_HEADER)).isEqualTo("chat");
        assertThat(res.getHeader("X-RateLimit-Limit")).isEqualTo("300");
        assertThat(res.getHeader("X-RateLimit-Remaining")).isEqualTo("299");
        verify(rateLimiter).tryAcquire(CHAT, TENANT, 300);
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt());
    }

    /** CR-205 TC-RL-010: 질문 말고 위젯이 보내는 다른 요청도 채팅 칸이다. */
    @ParameterizedTest
    @CsvSource({
            "GET, /api/v1/conversations",
            "GET, /api/v1/chat/me/preferences",
            "POST, /api/v1/chat/tts",
    })
    void tcRl010_otherWidgetRequestsUseChatBucket(String method, String path) throws Exception {
        authenticatedAs(ChatOwner.WIDGET_ROLE);

        assertThat(run(method, path, new MockHttpServletResponse())).isTrue();
        verify(rateLimiter).tryAcquire(eq(CHAT), eq(TENANT), anyInt());
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt());
    }

    /** CR-205 TC-RL-011: 로그인 토큰과 연동키(인증 정보 없음) 요청은 테넌트 칸 그대로다. */
    @Test
    void tcRl011_loginAndApiKeyRequestsUseTenantBucket() throws Exception {
        authenticatedAs("TENANT_ADMIN");
        var res = new MockHttpServletResponse();
        assertThat(run("POST", "/api/v1/chat/completions", res)).isFalse();
        assertThat(res.getHeader(RateLimitFilter.SCOPE_HEADER)).isEqualTo("tenant");

        SecurityContextHolder.clearContext();
        assertThat(run("POST", "/api/v1/workflows/wf-1/run", new MockHttpServletResponse())).isFalse();

        verify(rateLimiter, org.mockito.Mockito.times(2)).tryAcquire(TENANT, 60);
        verify(rateLimiter, never()).tryAcquire(eq(CHAT), anyString(), anyInt());
    }

    /** CR-205 TC-RL-012: 채팅 칸을 넘으면 코드 chat_rate_limited 와 scope chat 으로 거절한다. */
    @Test
    void tcRl012_chatOverLimitIsRejectedWithCode() throws Exception {
        when(rateLimiter.tryAcquire(eq(CHAT), anyString(), anyInt()))
                .thenReturn(TokenBucketRateLimiter.RateLimitResult.exceeded(5, 6, 12));
        authenticatedAs(ChatOwner.WIDGET_ROLE);
        var res = new MockHttpServletResponse();

        assertThat(run("POST", "/api/v1/chat/completions", res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("12");
        assertThat(res.getHeader(RateLimitFilter.SCOPE_HEADER)).isEqualTo("chat");
        assertThat(res.getContentAsString())
                .contains("\"error\":\"chat_rate_limited\"")
                .contains("\"scope\":\"chat\"")
                .contains("\"retry_after_seconds\":12");
    }

    /** CR-205 TC-RL-013: 테넌트 칸의 429 문장은 그대로고 scope 만 는다. */
    @Test
    void tcRl013_tenantRejectionKeepsSentence() throws Exception {
        var res = new MockHttpServletResponse();

        assertThat(run("POST", "/api/v1/workflows/wf-1/run", res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getContentAsString())
                .contains("\"error\":\"Rate limit exceeded. Max 60 requests per minute.\"")
                .contains("\"scope\":\"tenant\"");
    }

    /** CR-205 TC-RL-014: 채팅 상한은 구독 행, 없으면 기본 플랜의 chat-rpm-limit, 설정도 없으면 60. */
    @Test
    void tcRl014_chatLimitSource() throws Exception {
        authenticatedAs(ChatOwner.WIDGET_ROLE);

        when(subscriptions.findByTenantId(TENANT)).thenReturn(Optional.of(subscriptionWithLimits(60, 20)));
        run("GET", "/api/v1/conversations", new MockHttpServletResponse());
        verify(rateLimiter).tryAcquire(CHAT, TENANT, 20);

        when(subscriptions.findByTenantId(TENANT)).thenReturn(Optional.empty());
        when(settings.getString("plan.default", null)).thenReturn("free");
        when(settings.getString("plan.free.chat-rpm-limit", null)).thenReturn("30");
        run("GET", "/api/v1/conversations", new MockHttpServletResponse());
        verify(rateLimiter).tryAcquire(CHAT, TENANT, 30);

        when(settings.getString("plan.free.chat-rpm-limit", null)).thenReturn(null);
        run("GET", "/api/v1/conversations", new MockHttpServletResponse());
        verify(rateLimiter).tryAcquire(CHAT, TENANT, 60);
    }
}
