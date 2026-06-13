package com.platform.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JWT 토큰 생성 및 검증.
 */
@Component
public class JwtProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtProvider.class);

    private final SecretKey key;
    private final long accessExpirationMs;
    private final long refreshExpirationMs;

    public JwtProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms:1800000}") long accessExpirationMs,
            @Value("${jwt.refresh-expiration-ms:604800000}") long refreshExpirationMs
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirationMs = accessExpirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public String generateAccessToken(String userId, String email, String tenantId, String role) {
        Date now = new Date();
        // CR-096: Map.of 는 null 값을 거부한다. 플랫폼 전역 super admin 은 tenantId=null 이므로
        // HashMap 으로 만들고 null 일 때 tenant_id claim 자체를 생략한다(필터는 null 처리 가능).
        Map<String, Object> claims = new HashMap<>();
        claims.put("email", email);
        if (tenantId != null) claims.put("tenant_id", tenantId);
        claims.put("role", role);
        claims.put("type", "access");
        return Jwts.builder()
                .subject(userId)
                .claims(claims)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessExpirationMs))
                .signWith(key)
                .compact();
    }

    /**
     * CR-058: 위젯 임베드용 단기 토큰.
     * type=widget 으로 access 토큰과 구분된다. 서명 시크릿은 동일.
     * scope 는 JwtAuthenticationFilter 가 GrantedAuthority("SCOPE_*") 로 매핑한다.
     */
    public String generateWidgetToken(String tenantId, String projectId, String userRef,
                                      List<String> scopes, String origin, long ttlSeconds) {
        Date now = new Date();
        Map<String, Object> claims = new HashMap<>();
        claims.put("tenant_id", tenantId);
        if (projectId != null) claims.put("project_id", projectId);
        if (userRef != null) claims.put("user_ref", userRef);
        claims.put("scopes", scopes);
        if (origin != null) claims.put("origin", origin);
        claims.put("type", "widget");
        return Jwts.builder()
                .subject(userRef != null ? userRef : "widget")
                .claims(claims)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlSeconds * 1000L))
                .signWith(key)
                .compact();
    }

    /**
     * CR-096: Super Admin 테넌트 임퍼소네이션용 단기 access 토큰.
     * 대상 tenant_id 를 claim 에 박아 JwtAuthenticationFilter:118 의 헤더 일치 가드를 합법 통과한다.
     * impersonating=true + actor(관리자 email) 로 감사 추적 가능.
     * type=access 라서 일반 API 인증/RBAC 가 그대로 적용된다.
     */
    public String generateImpersonationToken(String adminUserId, String adminEmail,
                                             String targetTenantId, long ttlSeconds) {
        Date now = new Date();
        Map<String, Object> claims = new HashMap<>();
        claims.put("email", adminEmail);
        claims.put("tenant_id", targetTenantId);
        claims.put("role", "super_admin");
        claims.put("type", "access");
        claims.put("impersonating", true);
        claims.put("actor", adminEmail);
        return Jwts.builder()
                .subject(adminUserId)
                .claims(claims)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlSeconds * 1000L))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(String userId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(userId)
                .claims(Map.of("type", "refresh"))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + refreshExpirationMs))
                .signWith(key)
                .compact();
    }

    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            extractClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: {}", e.getMessage());
        } catch (JwtException e) {
            log.debug("JWT invalid: {}", e.getMessage());
        }
        return false;
    }

    public String getUserId(String token) {
        return extractClaims(token).getSubject();
    }

    public String getTokenType(String token) {
        return extractClaims(token).get("type", String.class);
    }
}
