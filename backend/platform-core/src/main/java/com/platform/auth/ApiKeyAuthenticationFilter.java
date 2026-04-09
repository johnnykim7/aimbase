package com.platform.auth;

import com.platform.domain.master.ApiKeyEntity;
import com.platform.domain.RoleEntity;
import com.platform.domain.UserEntity;
import com.platform.repository.master.ApiKeyRepository;
import com.platform.repository.RoleRepository;
import com.platform.repository.UserRepository;
import com.platform.tenant.TenantContext;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;

/**
 * API Key 인증 필터.
 * X-API-Key 헤더의 값을 SHA-256 해시하여 DB의 api_key_hash와 비교한다.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyAuthenticationFilter(UserRepository userRepository,
                                      RoleRepository roleRepository,
                                      ApiKeyRepository apiKeyRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // JWT 인증이 이미 완료된 경우 스킵
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String hash = sha256(apiKey);

            // 1) api_keys 테이블 우선 조회 (CR-025)
            ApiKeyEntity apiKeyEntity = apiKeyRepository.findByKeyHashAndIsActiveTrue(hash).orElse(null);
            if (apiKeyEntity != null) {
                // 만료 검증
                if (apiKeyEntity.getExpiresAt() != null
                        && apiKeyEntity.getExpiresAt().isBefore(OffsetDateTime.now())) {
                    log.debug("API Key expired: {}", apiKeyEntity.getKeyPrefix());
                    filterChain.doFilter(request, response);
                    return;
                }

                // last_used_at 비동기 갱신
                apiKeyEntity.setLastUsedAt(OffsetDateTime.now());
                apiKeyRepository.save(apiKeyEntity);

                // API Key의 tenant_id로 TenantContext 자동 설정 (X-Tenant-Id 헤더 불필요)
                // tenant_id가 null이면 도메인 전용 키 — 기존 TenantContext(헤더 기반) 유지
                if (apiKeyEntity.getTenantId() != null) {
                    TenantContext.setTenantId(apiKeyEntity.getTenantId());
                }

                // 시스템 키 — ADMIN 권한 부여
                String role = "admin";
                Map<String, Object> permissions = Map.of();

                String tenantId = apiKeyEntity.getTenantId() != null
                        ? apiKeyEntity.getTenantId()
                        : TenantContext.getTenantId();
                String userId = "system-" + apiKeyEntity.getId();
                String email = apiKeyEntity.getCreatedBy() != null ? apiKeyEntity.getCreatedBy() : "api-key@system";
                UserPrincipal principal = new UserPrincipal(userId, email, tenantId, role, permissions);
                setAuthentication(principal, request);
                filterChain.doFilter(request, response);
                return;
            }

            // 2) 기존 users.api_key_hash 폴백
            UserEntity user = userRepository.findByApiKeyHash(hash).orElse(null);
            if (user == null || !user.isActive()) {
                filterChain.doFilter(request, response);
                return;
            }

            String role = "viewer";
            Map<String, Object> permissions = Map.of();
            if (user.getRoleId() != null) {
                RoleEntity roleEntity = roleRepository.findById(user.getRoleId()).orElse(null);
                if (roleEntity != null) {
                    role = roleEntity.getId();
                    permissions = roleEntity.getPermissions();
                }
            }

            String tenantId = TenantContext.getTenantId();
            UserPrincipal principal = new UserPrincipal(user.getId(), user.getEmail(), tenantId, role, permissions);
            setAuthentication(principal, request);

        } catch (Exception e) {
            log.debug("API Key authentication failed: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private void setAuthentication(UserPrincipal principal, HttpServletRequest request) {
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
