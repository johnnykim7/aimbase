package com.platform.mcp;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CR-145 등록 config.headers 검증, 가림, 가린 값 되돌리기. */
class MCPServerHeadersTest {

    private static final String URL = "http://host:8183/api/mcp";

    @Test
    void from_returnsHeaders() {
        Map<String, String> headers = MCPServerHeaders.from(
                Map.of("url", URL, "headers", Map.of("X-API-Key", "secret-1")));

        assertThat(headers).containsExactly(Map.entry("X-API-Key", "secret-1"));
    }

    @Test
    void from_withoutHeaders_isEmpty() {
        assertThat(MCPServerHeaders.from(Map.of("url", URL))).isEmpty();
        assertThat(MCPServerHeaders.from(null)).isEmpty();
    }

    // TC-MCPH-006
    @Test
    void from_rejectsInvalidHeaders() {
        assertThatThrownBy(() -> MCPServerHeaders.from(Map.of("url", URL, "headers", "X-API-Key: v")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MCPServerHeaders.from(Map.of("url", URL, "headers", Map.of("X-API-Key", " "))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MCPServerHeaders.from(Map.of("url", URL, "headers", Map.of("X-API-Key", 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MCPServerHeaders.from(Map.of("url", URL, "headers", Map.of("Host", "evil"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MCPServerHeaders.from(Map.of("url", URL, "headers", Map.of("Bad Name", "v"))))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> duplicated = new LinkedHashMap<>();
        duplicated.put("X-API-Key", "a");
        duplicated.put("x-api-key", "b");
        assertThatThrownBy(() -> MCPServerHeaders.from(Map.of("url", URL, "headers", duplicated)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void from_errorMessageDoesNotContainValue() {
        assertThatThrownBy(() -> MCPServerHeaders.from(
                Map.of("url", URL, "headers", Map.of("X-API-Key", "secret-1\r\nInjected: x"))))
                .isInstanceOf(IllegalArgumentException.class)
                .message().doesNotContain("secret-1");
    }

    // TC-MCPH-004
    @Test
    void masked_replacesValuesAndKeepsOriginal() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("url", URL);
        config.put("headers", new LinkedHashMap<>(Map.of("X-API-Key", "secret-1")));

        Map<String, Object> masked = MCPServerHeaders.masked(config);

        assertThat(masked).containsEntry("url", URL)
                .containsEntry("headers", Map.of("X-API-Key", MCPServerHeaders.MASK));
        assertThat(config.get("headers")).isEqualTo(Map.of("X-API-Key", "secret-1"));
    }

    @Test
    void masked_withoutHeaders_isSameConfig() {
        Map<String, Object> config = Map.of("url", URL);

        assertThat(MCPServerHeaders.masked(config)).isSameAs(config);
    }

    // TC-MCPH-005
    @Test
    void restoreMasked_keepsStoredValueReplacesNewAndDropsMissing() {
        Map<String, Object> stored = Map.of("url", URL,
                "headers", Map.of("X-API-Key", "secret-1", "X-Old", "old"));
        Map<String, Object> incoming = Map.of("url", URL,
                "headers", Map.of("x-api-key", MCPServerHeaders.MASK, "X-New", "new"));

        Map<String, Object> restored = MCPServerHeaders.restoreMasked(stored, incoming);

        assertThat(restored.get("headers")).isEqualTo(Map.of("x-api-key", "secret-1", "X-New", "new"));
    }

    @Test
    void restoreMasked_rejectsMaskWithoutStoredValue() {
        Map<String, Object> incoming = Map.of("url", URL,
                "headers", Map.of("X-API-Key", MCPServerHeaders.MASK));

        assertThatThrownBy(() -> MCPServerHeaders.restoreMasked(Map.of("url", URL), incoming))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MCPServerHeaders.restoreMasked(null, incoming))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
