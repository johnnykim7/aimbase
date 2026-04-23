package com.platform.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-058: JwtProvider.generateWidgetToken() 검증.
 */
class JwtProviderWidgetTest {

    private JwtProvider provider;

    @BeforeEach
    void setUp() {
        // 최소 32자 HMAC-SHA256 키
        String secret = "cr058-widget-test-secret-key-which-is-long-enough-123456";
        provider = new JwtProvider(secret, 1800_000L, 604800_000L);
    }

    @Test
    void generateWidgetToken_containsAllClaims() {
        String token = provider.generateWidgetToken(
                "tenant-abc", "rescue", "user-123",
                List.of("chat:stream", "rag:read"),
                "https://oms.company.com", 1800L);

        Claims claims = provider.extractClaims(token);
        assertThat(claims.get("type", String.class)).isEqualTo("widget");
        assertThat(claims.get("tenant_id", String.class)).isEqualTo("tenant-abc");
        assertThat(claims.get("project_id", String.class)).isEqualTo("rescue");
        assertThat(claims.get("user_ref", String.class)).isEqualTo("user-123");
        assertThat(claims.get("origin", String.class)).isEqualTo("https://oms.company.com");
        @SuppressWarnings("unchecked")
        List<String> scopes = claims.get("scopes", List.class);
        assertThat(scopes).containsExactly("chat:stream", "rag:read");
        assertThat(claims.getSubject()).isEqualTo("user-123");
    }

    @Test
    void generateWidgetToken_expirationRespectsTtl() {
        String token = provider.generateWidgetToken(
                "t1", null, null, List.of("chat:stream"), null, 60L);

        Claims claims = provider.extractClaims(token);
        long remainingMs = claims.getExpiration().getTime() - System.currentTimeMillis();
        assertThat(remainingMs).isBetween(55_000L, 65_000L);
    }

    @Test
    void generateWidgetToken_withoutOptionalFieldsStillValid() {
        String token = provider.generateWidgetToken(
                "t1", null, null, List.of("chat:stream"), null, 60L);

        assertThat(provider.validateToken(token)).isTrue();
        Claims claims = provider.extractClaims(token);
        assertThat(claims.getSubject()).isEqualTo("widget"); // user_ref 없을 때 fallback
        assertThat(claims.get("project_id")).isNull();
        assertThat(claims.get("origin")).isNull();
    }

    @Test
    void tokenType_distinguishesWidgetFromAccess() {
        String widget = provider.generateWidgetToken(
                "t1", null, null, List.of("chat:stream"), null, 60L);
        String access = provider.generateAccessToken("u1", "a@b.com", "t1", "USER");

        assertThat(provider.getTokenType(widget)).isEqualTo("widget");
        assertThat(provider.getTokenType(access)).isEqualTo("access");
    }
}
