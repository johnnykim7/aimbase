package com.platform.tenant;

import com.platform.auth.JwtProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * TenantResolver 단위 테스트.
 * X-Tenant-Id 헤더, 서브도메인, 플랫폼 경로 스킵 검증.
 */
class TenantResolverTest {

    private TenantResolver resolver;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        resolver = new TenantResolver();
        response = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void doFilter_withTenantHeader_shouldSetTenantContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "acme");
        request.setRequestURI("/api/v1/connections");

        // chain 내에서 TenantContext 확인
        doAnswer(inv -> {
            assertThat(TenantContext.getTenantId()).isEqualTo("acme");
            return null;
        }).when(chain).doFilter(request, response);

        resolver.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        // finally에서 clear됨
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void doFilter_withSubdomain_shouldResolveTenant() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("tenant1.platform.com");
        request.setRequestURI("/api/v1/workflows");

        doAnswer(inv -> {
            assertThat(TenantContext.getTenantId()).isEqualTo("tenant1");
            return null;
        }).when(chain).doFilter(request, response);

        resolver.doFilter(request, response, chain);
    }

    @Test
    void doFilter_localhost_shouldNotSetTenant() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("localhost");
        request.setRequestURI("/api/v1/connections");

        doAnswer(inv -> {
            assertThat(TenantContext.getTenantId()).isNull();
            return null;
        }).when(chain).doFilter(request, response);

        resolver.doFilter(request, response, chain);
    }

    @Test
    void doFilter_wwwSubdomain_shouldNotSetTenant() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("www.platform.com");
        request.setRequestURI("/api/v1/connections");

        doAnswer(inv -> {
            assertThat(TenantContext.getTenantId()).isNull();
            return null;
        }).when(chain).doFilter(request, response);

        resolver.doFilter(request, response, chain);
    }

    @Test
    void doFilter_platformPath_shouldSkipTenantResolution() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "acme");
        request.setRequestURI("/api/v1/platform/tenants");

        doAnswer(inv -> {
            // 플랫폼 경로에서는 TenantContext 설정 안 됨
            assertThat(TenantContext.getTenantId()).isNull();
            return null;
        }).when(chain).doFilter(request, response);

        resolver.doFilter(request, response, chain);
    }

    @Test
    void doFilter_headerTakesPrecedenceOverSubdomain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "header-tenant");
        request.setServerName("subdomain-tenant.platform.com");
        request.setRequestURI("/api/v1/connections");

        doAnswer(inv -> {
            assertThat(TenantContext.getTenantId()).isEqualTo("header-tenant");
            return null;
        }).when(chain).doFilter(request, response);

        resolver.doFilter(request, response, chain);
    }

    @Test
    void doFilter_blankHeader_shouldFallbackToSubdomain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "  ");
        request.setServerName("fallback.platform.com");
        request.setRequestURI("/api/v1/connections");

        doAnswer(inv -> {
            assertThat(TenantContext.getTenantId()).isEqualTo("fallback");
            return null;
        }).when(chain).doFilter(request, response);

        resolver.doFilter(request, response, chain);
    }

    @Test
    void doFilter_chainThrows_shouldStillClearContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "acme");
        request.setRequestURI("/api/v1/connections");

        doThrow(new ServletException("chain error")).when(chain).doFilter(request, response);

        try {
            resolver.doFilter(request, response, chain);
        } catch (ServletException ignored) {
        }

        // finally에서 반드시 clear
        assertThat(TenantContext.getTenantId()).isNull();
    }

    /**
     * CR-058: CORS preflight(OPTIONS) 는 커스텀 헤더(X-Tenant-Id)를 포함하지 않는다.
     * Tenant 헤더 강제 검사를 건너뛰고 즉시 체인 위임해야 Spring CORS 가 동작한다.
     */
    @Test
    void doFilter_optionsRequest_shouldBypassTenantCheck() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("OPTIONS");
        request.setRequestURI("/api/v1/chat/completions");
        // X-Tenant-Id 헤더 없음 — 기존 로직이면 400, OPTIONS 분기로 통과해야 한다

        resolver.doFilter(request, response, chain);

        // 체인 전파 + 에러 응답 없음
        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200); // MockHttpServletResponse 기본값
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void doFilter_optionsRequestOnTenantRequiredPath_shouldNotReturn400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("OPTIONS");
        request.setRequestURI("/api/v1/workflows/runs/abc/subscribe");

        resolver.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isNotEqualTo(400);
    }

    /**
     * CR-058 후속: 위젯 JWT 의 tenant_id 클레임으로 TenantContext 가 설정되어야 한다.
     * 위젯 패턴은 토큰 하나로 인증+테넌트 식별이 모두 끝난다 — X-Tenant-Id 헤더 강제 금지.
     */
    @Test
    void doFilter_withWidgetTokenInBearer_shouldResolveTenantFromClaim() throws Exception {
        JwtProvider jwtProvider = new JwtProvider(
                "tenant-resolver-widget-test-secret-key-which-is-long-enough-1234", 1800_000L, 604800_000L);
        String token = jwtProvider.generateWidgetToken(
                "flowguard_dev", null, null, List.of("chat:stream"), null, 1800L);
        TenantResolver widgetResolver = new TenantResolver(stubProvider(jwtProvider));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/chat/completions");
        request.addHeader("Authorization", "Bearer " + token);

        doAnswer(inv -> {
            assertThat(TenantContext.getTenantId()).isEqualTo("flowguard_dev");
            return null;
        }).when(chain).doFilter(request, response);

        widgetResolver.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isNotEqualTo(400);
    }

    @Test
    void doFilter_withWidgetTokenInQueryParam_shouldResolveTenantFromClaim() throws Exception {
        JwtProvider jwtProvider = new JwtProvider(
                "tenant-resolver-widget-test-secret-key-which-is-long-enough-1234", 1800_000L, 604800_000L);
        String token = jwtProvider.generateWidgetToken(
                "flowguard_dev", null, null, List.of("chat:stream"), null, 1800L);
        TenantResolver widgetResolver = new TenantResolver(stubProvider(jwtProvider));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/chat/stream");
        request.setParameter("access_token", token);

        doAnswer(inv -> {
            assertThat(TenantContext.getTenantId()).isEqualTo("flowguard_dev");
            return null;
        }).when(chain).doFilter(request, response);

        widgetResolver.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilter_withAccessToken_shouldNotFallbackToTokenClaim() throws Exception {
        // access 토큰은 위젯 폴백 대상 아님 — JwtAuthenticationFilter 가 처리해야 한다.
        // 헤더/쿼리/서브도메인 모두 없으므로 tenant 필수 경로에서 400 이 떨어져야 정상.
        JwtProvider jwtProvider = new JwtProvider(
                "tenant-resolver-widget-test-secret-key-which-is-long-enough-1234", 1800_000L, 604800_000L);
        String accessToken = jwtProvider.generateAccessToken("u1", "a@b.com", "flowguard_dev", "USER");
        TenantResolver widgetResolver = new TenantResolver(stubProvider(jwtProvider));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/chat/completions");
        request.addHeader("Authorization", "Bearer " + accessToken);

        widgetResolver.doFilter(request, response, chain);

        // tenant 필수 경로 + 헤더/쿼리/서브도메인/위젯토큰 모두 없음 → 400
        assertThat(response.getStatus()).isEqualTo(400);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void doFilter_widgetToken_headerTakesPrecedenceOverTokenClaim() throws Exception {
        // X-Tenant-Id 헤더가 있으면 토큰 클레임보다 우선 (개발/테스트 편의).
        JwtProvider jwtProvider = new JwtProvider(
                "tenant-resolver-widget-test-secret-key-which-is-long-enough-1234", 1800_000L, 604800_000L);
        String token = jwtProvider.generateWidgetToken(
                "flowguard_dev", null, null, List.of("chat:stream"), null, 1800L);
        TenantResolver widgetResolver = new TenantResolver(stubProvider(jwtProvider));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/chat/completions");
        request.addHeader("X-Tenant-Id", "header-wins");
        request.addHeader("Authorization", "Bearer " + token);

        doAnswer(inv -> {
            assertThat(TenantContext.getTenantId()).isEqualTo("header-wins");
            return null;
        }).when(chain).doFilter(request, response);

        widgetResolver.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JwtProvider> stubProvider(JwtProvider jwtProvider) {
        ObjectProvider<JwtProvider> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(jwtProvider);
        return op;
    }
}
