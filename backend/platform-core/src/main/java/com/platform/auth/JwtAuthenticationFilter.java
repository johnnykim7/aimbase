package com.platform.auth;

import com.platform.domain.RoleEntity;
import com.platform.domain.UserEntity;
import com.platform.repository.RoleRepository;
import com.platform.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * JWT Bearer 토큰 인증 필터.
 * Authorization: Bearer {token} 헤더에서 토큰을 추출하여 검증한다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    public JwtAuthenticationFilter(JwtProvider jwtProvider,
                                   UserRepository userRepository,
                                   RoleRepository roleRepository) {
        this.jwtProvider = jwtProvider;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length());
        if (!jwtProvider.validateToken(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtProvider.extractClaims(token);
            String tokenType = claims.get("type", String.class);
            if (!"access".equals(tokenType)) {
                filterChain.doFilter(request, response);
                return;
            }

            String userId = claims.getSubject();
            String tenantId = claims.get("tenant_id", String.class);
            String role = claims.get("role", String.class);
            String email = claims.get("email", String.class);
            String path = request.getRequestURI();

            // Platform/App API는 TenantContext가 없으므로 JWT claims만으로 인증
            if (path.startsWith("/api/v1/platform") || path.startsWith("/api/v1/apps/")) {
                UserPrincipal principal = new UserPrincipal(userId, email, tenantId, role, Map.of());
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                UserEntity user = userRepository.findById(userId).orElse(null);
                if (user == null || !user.isActive()) {
                    filterChain.doFilter(request, response);
                    return;
                }

                Map<String, Object> permissions = Map.of();
                if (user.getRoleId() != null) {
                    permissions = roleRepository.findById(user.getRoleId())
                            .map(RoleEntity::getPermissions)
                            .orElse(Map.of());
                }

                UserPrincipal principal = new UserPrincipal(userId, user.getEmail(), tenantId, role, permissions);
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }

        } catch (Exception e) {
            log.error("JWT authentication failed: {}", e.getMessage(), e);
        }

        filterChain.doFilter(request, response);
    }
}
