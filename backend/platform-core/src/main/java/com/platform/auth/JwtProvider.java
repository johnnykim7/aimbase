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
        return Jwts.builder()
                .subject(userId)
                .claims(Map.of(
                        "email", email,
                        "tenant_id", tenantId,
                        "role", role,
                        "type", "access"
                ))
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
