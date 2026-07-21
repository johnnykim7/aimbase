package com.platform.mcp.agent;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-124 e2e — 포팅한 Streamable HTTP 서버를 실제로 띄우고 MCP 클라이언트로 핸드셰이크한다.
 * 컴파일 성공이 아니라 "런타임에 2025-11-25 로 협상되고 도구가 보이는가"를 검증.
 */
class Cr124E2eHandshakeTest {

    @Test
    void realHandshake_negotiates_2025_11_25_andListsTool() throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(io.modelcontextprotocol.json.McpJsonDefaults.getMapper())
                .mcpEndpoint("/mcp")
                .build();

        var tool = McpSchema.Tool.builder()
                .name("probe_ping")
                .description("probe")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .build();

        McpServer.sync(transport)
                .serverInfo("aimbase-cr124", "1.0.0")
                .tools(new McpServerFeatures.SyncToolSpecification(tool,
                        (exchange, request) -> McpSchema.CallToolResult.builder()
                                .addTextContent("pong").isError(false).build()))
                .build();

        var tomcat = new Tomcat();
        tomcat.setPort(0);
        tomcat.getConnector();
        var ctx = tomcat.addContext("", null);
        Tomcat.addServlet(ctx, "mcp", transport).setAsyncSupported(true);
        ctx.addServletMappingDecoded("/mcp/*", "mcp");
        tomcat.start();
        int port = tomcat.getConnector().getLocalPort();

        try {
            var client = McpClient.sync(
                    HttpClientStreamableHttpTransport
                            .builder("http://localhost:" + port)
                            .endpoint("/mcp")
                            .build())
                    .build();

            McpSchema.InitializeResult init = client.initialize();
            System.out.println("[CR-124] negotiated protocolVersion = " + init.protocolVersion());

            assertThat(init.protocolVersion()).isEqualTo(ProtocolVersions.MCP_2025_11_25);
            assertThat(client.listTools().tools())
                    .extracting(McpSchema.Tool::name)
                    .contains("probe_ping");

            var result = client.callTool(new McpSchema.CallToolRequest("probe_ping", Map.of()));
            assertThat(result.isError()).isFalse();
            client.close();
        } finally {
            tomcat.stop();
            tomcat.destroy();
        }
    }
}
