package com.platform.llm;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionApiKeysTest {

    private static final Map<String, String> ENV = Map.of("BP_OFFICE_OPENAI_API_KEY", "k-env", "BLANK_ENV", " ");

    @Test
    void valueInConfigComesFirst() {
        assertThat(ConnectionApiKeys.resolve(config("k-plain", "BP_OFFICE_OPENAI_API_KEY"), ENV::get))
                .isEqualTo("k-plain");
    }

    @Test
    void readsEnvironmentVariableWhenValueIsMissingOrBlank() {
        assertThat(ConnectionApiKeys.resolve(config(null, "BP_OFFICE_OPENAI_API_KEY"), ENV::get))
                .isEqualTo("k-env");
        assertThat(ConnectionApiKeys.resolve(config("", "BP_OFFICE_OPENAI_API_KEY"), ENV::get))
                .isEqualTo("k-env");
    }

    @Test
    void returnsNullWhenNothingUsable() {
        assertThat(ConnectionApiKeys.resolve(null, ENV::get)).isNull();
        assertThat(ConnectionApiKeys.resolve(config(null, null), ENV::get)).isNull();
        assertThat(ConnectionApiKeys.resolve(config(null, "NOT_SET"), ENV::get)).isNull();
        assertThat(ConnectionApiKeys.resolve(config(null, "BLANK_ENV"), ENV::get)).isNull();
    }

    private static Map<String, Object> config(String apiKey, String apiKeyEnv) {
        Map<String, Object> cfg = new HashMap<>();
        if (apiKey != null) cfg.put("apiKey", apiKey);
        if (apiKeyEnv != null) cfg.put("apiKey_env", apiKeyEnv);
        return cfg;
    }
}
