package com.platform.runner.claudecli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeCliAdapterConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void emits_empty_mcpServers_when_nothing_configured() throws Exception {
        ClaudeCliAdapterConfig cfg = new ClaudeCliAdapterConfig();
        JsonNode root = MAPPER.readTree(cfg.resolveMcpConfigJson());
        assertThat(root.has("mcpServers")).isTrue();
        assertThat(root.get("mcpServers").size()).isEqualTo(0);
    }

    @Test
    void emits_aimbase_legacy_key_when_only_local_jar() throws Exception {
        ClaudeCliAdapterConfig cfg = new ClaudeCliAdapterConfig();
        cfg.setAimbaseAgentJar("/opt/aimbase/aimbase-agent.jar");

        JsonNode root = MAPPER.readTree(cfg.resolveMcpConfigJson());
        JsonNode servers = root.get("mcpServers");
        // 호환 모드: 기존 키 'aimbase' 유지
        assertThat(servers.has("aimbase")).isTrue();
        assertThat(servers.has("aimbase-local")).isFalse();
        assertThat(servers.has("aimbase-server")).isFalse();
        assertThat(servers.get("aimbase").get("command").asText()).isEqualTo("java");
    }

    @Test
    void emits_dual_keys_when_server_exposure_enabled() throws Exception {
        ClaudeCliAdapterConfig cfg = new ClaudeCliAdapterConfig();
        cfg.setAimbaseAgentJar("/opt/aimbase/aimbase-agent.jar");
        cfg.setServerMcpBaseUrl("https://aimbase.example.com");
        cfg.setServerMcpApiKey("k-secret");
        cfg.setServerMcpAgentId("agent-uuid-1");

        JsonNode root = MAPPER.readTree(cfg.resolveMcpConfigJson());
        JsonNode servers = root.get("mcpServers");

        assertThat(servers.has("aimbase-local")).isTrue();
        assertThat(servers.has("aimbase-server")).isTrue();
        assertThat(servers.has("aimbase")).isFalse();

        JsonNode server = servers.get("aimbase-server");
        assertThat(server.get("type").asText()).isEqualTo("sse");
        assertThat(server.get("url").asText()).isEqualTo("https://aimbase.example.com/mcp/sse");
        JsonNode headers = server.get("headers");
        assertThat(headers.get("X-API-Key").asText()).isEqualTo("k-secret");
        assertThat(headers.get("X-Aimbase-Agent-Id").asText()).isEqualTo("agent-uuid-1");
    }

    @Test
    void server_exposure_only_emits_server_key() throws Exception {
        ClaudeCliAdapterConfig cfg = new ClaudeCliAdapterConfig();
        cfg.setServerMcpBaseUrl("https://aimbase.example.com/");
        cfg.setServerMcpApiKey("k-secret");

        JsonNode root = MAPPER.readTree(cfg.resolveMcpConfigJson());
        JsonNode servers = root.get("mcpServers");

        assertThat(servers.has("aimbase-server")).isTrue();
        assertThat(servers.has("aimbase-local")).isFalse();
        assertThat(servers.has("aimbase")).isFalse();
        // 끝 슬래시 자동 정규화
        assertThat(servers.get("aimbase-server").get("url").asText())
                .isEqualTo("https://aimbase.example.com/mcp/sse");
    }

    @Test
    void explicit_mcpConfigJson_takes_precedence() throws Exception {
        ClaudeCliAdapterConfig cfg = new ClaudeCliAdapterConfig();
        String custom = "{\"mcpServers\":{\"custom\":{\"url\":\"http://x/y\"}}}";
        cfg.setMcpConfigJson(custom);
        cfg.setAimbaseAgentJar("/opt/x.jar"); // 무시되어야 함
        cfg.setServerMcpBaseUrl("https://ignored");

        assertThat(cfg.resolveMcpConfigJson()).isEqualTo(custom);
    }

    @Test
    void omits_headers_when_no_credentials() throws Exception {
        ClaudeCliAdapterConfig cfg = new ClaudeCliAdapterConfig();
        cfg.setServerMcpBaseUrl("https://aimbase.example.com");

        JsonNode root = MAPPER.readTree(cfg.resolveMcpConfigJson());
        JsonNode server = root.get("mcpServers").get("aimbase-server");
        assertThat(server.has("headers")).isFalse();
    }
}
