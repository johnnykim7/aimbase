package com.platform.auth;

import com.platform.repository.RoleRepository;
import com.platform.repository.UserRepository;
import com.platform.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-058: JwtAuthenticationFilter 의 widget 토큰 분기 검증.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterWidgetTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    private JwtProvider jwtProvider;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(
                "cr058-filter-widget-test-secret-which-is-long-enough-1234",
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

    @Test
    void widgetToken_authenticatesWithScopeAuthorities() throws Exception {
        String token = jwtProvider.generateWidgetToken(
                "tenant-abc", "rescue", "user-1",
                List.of("chat:stream", "rag:read"),
                "https://oms.com", 60L);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getHeader("Origin")).thenReturn("https://oms.com");

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("SCOPE_chat:stream", "SCOPE_rag:read");
        assertThat(TenantContext.getTenantId()).isEqualTo("tenant-abc");
        verify(chain).doFilter(request, response);
    }

    @Test
    void widgetToken_rejectsWhenOriginMismatch() throws Exception {
        String token = jwtProvider.generateWidgetToken(
                "tenant-abc", null, null,
                List.of("chat:stream"),
                "https://oms.com", 60L);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getHeader("Origin")).thenReturn("https://evil.com");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response); // 필터는 체인 전파하되 인증 미설정
    }

    @Test
    void widgetToken_skipsOriginCheckWhenHeaderAbsent() throws Exception {
        // 서버간 헬스체크/테스트용 호출은 Origin 헤더 없이 올 수 있다.
        String token = jwtProvider.generateWidgetToken(
                "tenant-abc", null, null,
                List.of("chat:stream"),
                "https://oms.com", 60L);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getHeader("Origin")).thenReturn(null);

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("SCOPE_chat:stream");
    }

    @Test
    void widgetToken_acceptedViaQueryParamForSse() throws Exception {
        String token = jwtProvider.generateWidgetToken(
                "tenant-abc", null, null,
                List.of("workflow:subscribe"),
                null, 60L);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getParameter("access_token")).thenReturn(token);

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("SCOPE_workflow:subscribe");
    }

    @Test
    void accessToken_notAcceptedViaQueryParam() throws Exception {
        // access 토큰은 로그 유출 리스크로 쿼리 전달 금지 — 인증 미설정 채 통과
        String access = jwtProvider.generateAccessToken("u1", "a@b.com", "t1", "USER");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getParameter("access_token")).thenReturn(access);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }
}
