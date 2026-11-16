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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CR-196 TC-TOK-021: 임퍼소네이션 토큰이면 요청 주체에 그 표시가 실려 1년 토큰 넣기가 by=platform 이 된다. */
class JwtAuthenticationFilterImpersonationTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private JwtProvider jwtProvider;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider("cr196-impersonation-test-secret-which-is-long-enough-1234",
                1800_000L, 604800_000L);
        filter = new JwtAuthenticationFilter(jwtProvider, userRepository, mock(RoleRepository.class));
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void impersonationTokenMarksPrincipal() throws Exception {
        String token = jwtProvider.generateImpersonationToken("sa-1", "ops@aimbase.io", "tenant-1", 600);

        UserPrincipal p = authenticate(token);

        assertThat(p.isImpersonating()).isTrue();
        assertThat(p.getId()).isEqualTo("sa-1");
        assertThat(p.getEmail()).isEqualTo("ops@aimbase.io");
    }

    @Test
    void normalTokenIsNotImpersonating() throws Exception {
        UserEntity user = new UserEntity();
        user.setId("user-1");
        user.setEmail("admin@t1.com");
        user.setActive(true);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        String token = jwtProvider.generateAccessToken("user-1", "admin@t1.com", "tenant-1", "admin");

        assertThat(authenticate(token).isImpersonating()).isFalse();
    }

    private UserPrincipal authenticate(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/connections/c1/account-token");
        request.addHeader("Authorization", "Bearer " + token);
        TenantContext.setTenantId("tenant-1");
        filter.doFilterInternal(request, new MockHttpServletResponse(), mock(FilterChain.class));
        return (UserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
