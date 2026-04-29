package com.platform.auth;

import com.platform.domain.RoleEntity;
import com.platform.domain.UserEntity;
import com.platform.repository.RoleRepository;
import com.platform.repository.UserRepository;
import com.platform.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
        String token = null;
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            token = header.substring(BEARER_PREFIX.length());
        } else {
            // CR-058: EventSource 는 커스텀 헤더 불가 → ?access_token= 쿼리 폴백.
            // 단, access 토큰은 로그 유출 리스크로 헤더 전용 유지 — widget 토큰만 허용.
            String queryToken = request.getParameter("access_token");
            if (queryToken != null && jwtProvider.validateToken(queryToken)) {
                try {
                    Claims probe = jwtProvider.extractClaims(queryToken);
                    if ("widget".equals(probe.get("type", String.class))) {
                        token = queryToken;
                    }
                } catch (Exception ignored) {
                    // fall through
                }
            }
        }

        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!jwtProvider.validateToken(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtProvider.extractClaims(token);
            String tokenType = claims.get("type", String.class);

            // CR-058: widget 토큰 분기
            if ("widget".equals(tokenType)) {
                authenticateWidget(claims, request, response);
                filterChain.doFilter(request, response);
                return;
            }

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

    /**
     * CR-058: 위젯 토큰 인증.
     * - origin claim 과 요청 Origin 헤더 대조 (헤더 없으면 통과 — 서버간 테스트 허용)
     * - scopes claim 을 GrantedAuthority("SCOPE_*") 로 매핑
     * - TenantContext 설정 (TenantResolver 가 설정 못 했을 경우 대비)
     */
    private void authenticateWidget(Claims claims,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        String originClaim = claims.get("origin", String.class);
        String requestOrigin = request.getHeader("Origin");
        if (requestOrigin != null && originClaim != null && !requestOrigin.equals(originClaim)) {
            log.warn("Widget token origin mismatch — claim='{}', request='{}'", originClaim, requestOrigin);
            return;
        }

        String tenantId = claims.get("tenant_id", String.class);
        if (tenantId != null && !TenantContext.hasTenant()) {
            TenantContext.setTenantId(tenantId);
        }

        @SuppressWarnings("unchecked")
        List<String> scopes = claims.get("scopes", List.class);
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (scopes != null) {
            for (String s : scopes) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + s));
            }
        }

        String userRef = claims.get("user_ref", String.class);
        String subject = claims.getSubject();
        String principalId = userRef != null ? userRef : (subject != null ? subject : "widget");

        // CR-075: ClaudeCliAdapter 자동 라우팅용 — 토큰의 user_ref 를 ThreadLocal 에 주입.
        // AgentIdRequestFilter 의 finally 가 요청 종료 시 clear 한다.
        if (userRef != null && !userRef.isBlank()) {
            com.platform.llm.adapter.RequestContext.setUserRef(userRef);
        }

        UserPrincipal principal = new UserPrincipal(
                principalId, principalId, tenantId, "WIDGET", Map.of());

        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
