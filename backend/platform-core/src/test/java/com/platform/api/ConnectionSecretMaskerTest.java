package com.platform.api;

import com.platform.domain.ConnectionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("연결 조회 응답의 인증키 가림 (CR-197)")
class ConnectionSecretMaskerTest {

    private static ConnectionEntity httpConnection(Map<String, Object> auth) {
        ConnectionEntity e = new ConnectionEntity();
        e.setId("law-api");
        e.setName("법제처");
        e.setAdapter("http");
        e.setType("HTTP");
        e.setStatus("connected");
        e.setCreatedAt(OffsetDateTime.parse("2026-11-16T06:00:00+09:00"));
        e.setUpdatedAt(OffsetDateTime.parse("2026-11-16T06:10:00+09:00"));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("baseUrl", "https://www.law.go.kr");
        config.put("auth", new LinkedHashMap<>(auth));
        e.setConfig(config);
        return e;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> auth(ConnectionEntity e) {
        return (Map<String, Object>) e.getConfig().get("auth");
    }

    @Test
    void masks_copy_and_keeps_stored_value() {
        ConnectionEntity stored = httpConnection(Map.of(
                "type", "API_KEY", "in", "query", "name", "OC", "value", "real-key"));

        ConnectionEntity masked = ConnectionSecretMasker.masked(stored);

        assertThat(masked).isNotSameAs(stored);
        assertThat(auth(masked).get("value")).isEqualTo("***");
        assertThat(auth(masked).get("name")).isEqualTo("OC");
        assertThat(masked.getConfig().get("baseUrl")).isEqualTo("https://www.law.go.kr");
        assertThat(masked.getId()).isEqualTo("law-api");
        assertThat(masked.getStatus()).isEqualTo("connected");
        assertThat(masked.getCreatedAt()).isEqualTo(stored.getCreatedAt());
        assertThat(masked.getUpdatedAt()).isEqualTo(stored.getUpdatedAt());
        assertThat(auth(stored).get("value")).isEqualTo("real-key");
    }

    @Test
    void value_env_only_connection_is_returned_as_is() {
        ConnectionEntity stored = httpConnection(Map.of(
                "type", "API_KEY", "in", "query", "name", "OC", "value_env", "LAW_OC_KEY"));

        ConnectionEntity masked = ConnectionSecretMasker.masked(stored);

        assertThat(masked).isSameAs(stored);
        assertThat(auth(masked).get("value_env")).isEqualTo("LAW_OC_KEY");
    }

    @Test
    void connection_without_auth_is_returned_as_is() {
        ConnectionEntity llm = new ConnectionEntity();
        llm.setId("claude");
        llm.setConfig(Map.of("model", "claude-sonnet"));

        assertThat(ConnectionSecretMasker.masked(llm)).isSameAs(llm);
    }

    @Test
    void update_with_mask_keeps_stored_value() {
        Map<String, Object> stored = httpConnection(Map.of(
                "type", "API_KEY", "in", "query", "name", "OC", "value", "real-key")).getConfig();
        Map<String, Object> incoming = httpConnection(Map.of(
                "type", "API_KEY", "in", "query", "name", "OC", "value", "***")).getConfig();
        incoming.put("readTimeoutMs", 5000);

        Map<String, Object> merged = ConnectionSecretMasker.keepStoredAuthValue(incoming, stored);

        assertThat(((Map<?, ?>) merged.get("auth")).get("value")).isEqualTo("real-key");
        assertThat(merged.get("readTimeoutMs")).isEqualTo(5000);
    }

    @Test
    void update_with_new_value_replaces_it() {
        Map<String, Object> stored = httpConnection(Map.of(
                "type", "API_KEY", "in", "query", "name", "OC", "value", "real-key")).getConfig();
        Map<String, Object> incoming = httpConnection(Map.of(
                "type", "API_KEY", "in", "query", "name", "OC", "value", "rotated-key")).getConfig();

        Map<String, Object> merged = ConnectionSecretMasker.keepStoredAuthValue(incoming, stored);

        assertThat(((Map<?, ?>) merged.get("auth")).get("value")).isEqualTo("rotated-key");
    }
}
