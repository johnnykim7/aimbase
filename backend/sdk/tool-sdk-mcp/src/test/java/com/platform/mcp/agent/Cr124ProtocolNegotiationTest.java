package com.platform.mcp.agent;

import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-124 근거 테스트 — "SDK 2.0.0 업그레이드만으로는 해결되지 않는다"를 코드로 고정한다.
 */
class Cr124ProtocolNegotiationTest {

    @Test
    void sseTransport_advertisesOnly_2024_11_05() {
        var sse = HttpServletSseServerTransportProvider.builder()
                .jsonMapper(io.modelcontextprotocol.json.McpJsonDefaults.getMapper())
                .messageEndpoint("/mcp/message")
                .build();
        // SDK 2.0.0 이어도 SSE 는 2024-11-05 단일 → CLI 가 요구하는 2025-11-25 협상 불가
        assertThat(sse.protocolVersions()).containsExactly(ProtocolVersions.MCP_2024_11_05);
        assertThat(sse.protocolVersions()).doesNotContain(ProtocolVersions.MCP_2025_11_25);
    }

    @Test
    void streamableTransport_supports_2025_11_25() {
        var streamable = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(io.modelcontextprotocol.json.McpJsonDefaults.getMapper())
                .mcpEndpoint("/mcp")
                .build();
        // Streamable HTTP 만이 CLI 요구 버전을 포함한다 = CR-124 해결의 실제 조건
        assertThat(streamable.protocolVersions()).contains(ProtocolVersions.MCP_2025_11_25);
    }
}
