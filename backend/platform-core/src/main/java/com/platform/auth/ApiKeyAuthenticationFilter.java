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
 * X-API-Key 헤더 또는 Authorization: ApiKey 헤더의 값을 SHA-256 해시하여 DB의 api_key_hash와 비교한다.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";
    /**
     * CR-154: Claude CLI 는 MCP 접속 기록에 머리글을 그대로 적고, 이름이 Authorization 으로 끝나는 머리글만 가린다.
     * 키를 기록에 남기지 않으려는 호출자(실행기 CLI)는 이 형식으로 보낸다. 스킴 이름은 대소문자를 가리지 않는다(RFC 7235).
     */
    private static final String AUTHORIZATION_SCHEME = "ApiKey ";

    /** CR-150: 테넌트 없이 발급한 키로 인증한 요청 표시. 관리 도구(MCP)가 이 키를 거부한다. */
    public static final String TENANTLESS_KEY_ATTR = ApiKeyAuthenticationFilter.class.getName() + ".tenantlessKey";

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

        String apiKey = resolveApiKey(request);
        if (apiKey == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // CR-157: 다음 단계 호출은 try 밖에 둔다. 안에 두면 컨트롤러에서 난 예외를 키 확인 실패로 삼키고
        // 같은 요청을 한 번 더 흘려, 두 번 처리되거나 진짜 오류 대신 본문 없음 400 이 나간다.
        UserPrincipal principal = null;
        try {
            principal = authenticate(apiKey, request);
        } catch (Exception e) {
            log.warn("API Key authentication failed: {}", e.getMessage());
        }
        if (principal != null) {
            setAuthentication(principal, request);
        }
        filterChain.doFilter(request, response);
    }

    /** 키에 맞는 주체. 맞는 키가 없거나 만료됐으면 null. */
    private UserPrincipal authenticate(String apiKey, HttpServletRequest request) {
        String hash = sha256(apiKey);

        // 1) api_keys 테이블 우선 조회 (CR-025)
        ApiKeyEntity apiKeyEntity = apiKeyRepository.findByKeyHashAndIsActiveTrue(hash).orElse(null);
        if (apiKeyEntity != null) {
            // 만료 검증
            if (apiKeyEntity.getExpiresAt() != null
                    && apiKeyEntity.getExpiresAt().isBefore(OffsetDateTime.now())) {
                log.debug("API Key expired: {}", apiKeyEntity.getKeyPrefix());
                return null;
            }

            // last_used_at 비동기 갱신
            apiKeyEntity.setLastUsedAt(OffsetDateTime.now());
            apiKeyRepository.save(apiKeyEntity);

            // API Key의 tenant_id로 TenantContext 자동 설정 (X-Tenant-Id 헤더 불필요)
            // tenant_id가 null이면 도메인 전용 키 — 기존 TenantContext(헤더 기반) 유지
            if (apiKeyEntity.getTenantId() != null) {
                TenantContext.setTenantId(apiKeyEntity.getTenantId());
            } else {
                request.setAttribute(TENANTLESS_KEY_ATTR, Boolean.TRUE);
            }

            // 시스템 키 — ADMIN 권한 부여
            String role = "admin";
            Map<String, Object> permissions = Map.of();

            String tenantId = apiKeyEntity.getTenantId() != null
                    ? apiKeyEntity.getTenantId()
                    : TenantContext.getTenantId();
            String userId = "system-" + apiKeyEntity.getId();
            String email = apiKeyEntity.getCreatedBy() != null ? apiKeyEntity.getCreatedBy() : "api-key@system";
            return new UserPrincipal(userId, email, tenantId, role, permissions);
        }

        // 2) 기존 users.api_key_hash 폴백
        // CR-157: users 는 테넌트 DB 에만 있다. 테넌트가 정해지지 않은 요청은 master 로 가서 없는 표를 찾다 오류 기록만 남긴다.
        if (TenantContext.getTenantId() == null) {
            return null;
        }
        UserEntity user = userRepository.findByApiKeyHash(hash).orElse(null);
        if (user == null || !user.isActive()) {
            return null;
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

        return new UserPrincipal(user.getId(), user.getEmail(), TenantContext.getTenantId(), role, permissions);
    }

    /** 요청에 실린 API 키. X-API-Key 가 우선이고, 없으면 Authorization: ApiKey 값이다. 둘 다 없으면 null. */
    public static String resolveApiKey(HttpServletRequest request) {
        String key = request.getHeader(API_KEY_HEADER);
        if (key != null && !key.isBlank()) {
            return key;
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, AUTHORIZATION_SCHEME, 0, AUTHORIZATION_SCHEME.length())) {
            String value = auth.substring(AUTHORIZATION_SCHEME.length()).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
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
