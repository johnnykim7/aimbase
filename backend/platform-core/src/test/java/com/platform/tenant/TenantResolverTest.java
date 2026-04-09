package com.platform.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

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
}
