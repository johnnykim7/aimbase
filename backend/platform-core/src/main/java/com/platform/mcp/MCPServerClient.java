package com.platform.mcp;

import com.platform.llm.model.UnifiedToolDef;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 단일 MCP 서버와의 연결을 관리하는 클라이언트.
 *
 * MCP Java SDK 0.10.0 사용.
 * 현재 지원 transport: http/sse
 * - "http", "sse" → HttpClientSseClientTransport
 */
public class MCPServerClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MCPServerClient.class);

    private final String serverId;
    private final McpSyncClient client;
    private boolean initialized = false;

    public MCPServerClient(String serverId, String transport, Map<String, Object> config) {
        this.serverId = serverId;

        String url = (String) config.get("url");
        if (url == null) {
            throw new IllegalArgumentException("MCP server config must contain 'url' for transport: " + transport);
        }

        var httpTransport = HttpClientSseClientTransport.builder(url).build();
        this.client = McpClient.sync(httpTransport)
                .clientInfo(new McpSchema.Implementation("aimbase", "1.0.0"))
                .requestTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * MCP 서버 초기화 핸드쉐이크 수행.
     * listTools(), callTool() 호출 전에 반드시 호출해야 함.
     */
    public void connect() {
        if (!initialized) {
            McpSchema.InitializeResult result = client.initialize();
            initialized = true;
            log.info("Connected to MCP server '{}' — serverInfo={}, protocol={}",
                    serverId,
                    result.serverInfo() != null ? result.serverInfo().name() : "unknown",
                    result.protocolVersion());
        }
    }

    /**
     * MCP 서버에서 사용 가능한 도구 목록 조회.
     * @return UnifiedToolDef 목록 (ToolRegistry에 등록 가능한 형태)
     */
    public List<UnifiedToolDef> discoverTools() {
        ensureConnected();
        List<McpSchema.Tool> mcpTools = client.listTools().tools();
        log.info("Discovered {} tool(s) from MCP server '{}'", mcpTools.size(), serverId);
        return mcpTools.stream()
                .map(this::toUnifiedToolDef)
                .toList();
    }

    /**
     * MCP 서버에서 특정 도구 실행.
     * @param toolName 도구 이름
     * @param input    도구 입력 파라미터
     * @return 실행 결과 문자열
     */
    public String callTool(String toolName, Map<String, Object> input) {
        ensureConnected();
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(toolName, input));

        if (Boolean.TRUE.equals(result.isError())) {
            log.warn("MCP tool '{}' returned an error result", toolName);
        }

        return result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }

    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() {
        try {
            client.close();
            initialized = false;
            log.info("Disconnected from MCP server '{}'", serverId);
        } catch (Exception e) {
            log.warn("Error closing MCP client for server '{}': {}", serverId, e.getMessage());
        }
    }

    private void ensureConnected() {
        if (!initialized) {
            connect();
        }
    }

    private UnifiedToolDef toUnifiedToolDef(McpSchema.Tool tool) {
        Map<String, Object> schema = buildSchema(tool.inputSchema());
        return new UnifiedToolDef(tool.name(), tool.description(), schema);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSchema(McpSchema.JsonSchema jsonSchema) {
        if (jsonSchema == null) {
            return Map.of("type", "object", "properties", Map.of());
        }
        // JsonSchema의 properties, required, type 등을 Map으로 변환
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (jsonSchema.type() != null) result.put("type", jsonSchema.type());
        if (jsonSchema.properties() != null) result.put("properties", jsonSchema.properties());
        if (jsonSchema.required() != null) result.put("required", jsonSchema.required());
        if (jsonSchema.additionalProperties() != null)
            result.put("additionalProperties", jsonSchema.additionalProperties());
        if (result.isEmpty()) {
            result.put("type", "object");
            result.put("properties", Map.of());
        }
        return result;
    }
}
