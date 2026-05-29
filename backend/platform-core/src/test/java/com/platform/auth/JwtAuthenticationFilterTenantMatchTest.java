package com.platform.auth;

import com.platform.domain.UserEntity;
import com.platform.repository.RoleRepository;
import com.platform.repository.UserRepository;
import com.platform.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * CR-086: access 토큰 tenant_id claim ↔ X-Tenant-Id 헤더(TenantContext) 일치 강제 단위 테스트.
 *
 * Widget 테스트(JwtAuthenticationFilterWidgetTest)와 동일하게 실제 JwtProvider 로 진짜 토큰을 생성한다
 * (Claims mock 의 strict-stubbing 취약성 회피).
 */
class JwtAuthenticationFilterTenantMatchTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);

    private JwtProvider jwtProvider;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(
                "cr086-tenant-match-test-secret-which-is-long-enough-1234",
                1800_000L, 604800_000L);
        filter = new JwtAuthenticationFilter(jwtProvider, userRepository, roleRepository);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private String accessToken(String tenantId) {
        return jwtProvider.generateAccessToken("user-1", "u@example.com", tenantId, "USER");
    }

    private void stubActiveUser() {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setEmail("u@example.com");
        user.setActive(true);
        user.setRoleId(null);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
    }

    private MockHttpServletRequest req(String uri, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    @Test
    void tenantMatches_authenticates() throws Exception {
        stubActiveUser();
        MockHttpServletRequest request = req("/api/v1/mcp-servers", accessToken("tenant-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // TenantResolver 가 헤더에서 이미 설정한 상태 시뮬레이션
        TenantContext.setTenantId("tenant-1");

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(request, response);
    }

    @Test
    void tenantMismatch_returns403_andDoesNotAuthenticate() throws Exception {
        MockHttpServletRequest request = req("/api/v1/mcp-servers", accessToken("tenant-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // 토큰은 tenant-1 인데 헤더(context)는 tenant-2 — cross-tenant 사칭
        TenantContext.setTenantId("tenant-2");

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, never()).doFilter(request, response);
        verify(userRepository, never()).findById(anyString());
    }

    @Test
    void noHeaderTenant_trustsTokenClaim() throws Exception {
        stubActiveUser();
        MockHttpServletRequest request = req("/api/v1/mcp-servers", accessToken("tenant-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // TenantContext 미설정 (헤더 없음) → 토큰 claim 신뢰, 기존 동작 보존
        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(request, response);
    }

    @Test
    void platformPath_skipsTenantMatch() throws Exception {
        MockHttpServletRequest request = req("/api/v1/platform/tenants", accessToken("tenant-1"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // platform 경로는 TenantContext 가 있어도 대조 대상 아님 (Master DB 경로)
        TenantContext.setTenantId("tenant-2");

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(chain).doFilter(request, response);
    }
}
