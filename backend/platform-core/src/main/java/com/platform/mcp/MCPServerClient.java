package com.platform.mcp;

import com.platform.tool.model.UnifiedToolDef;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
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

        // SDK 0.10.0 의 HttpClientSseClientTransport 는 baseUri + sseEndpoint("/sse" 기본)
        // 형태로 호출. Java URI.resolve 는 절대경로(sseEndpoint) 가 base path 를 덮어쓰므로,
        // baseUri 에 context path(`/api/mcp`) 가 포함돼 있으면 호출이 root(`/sse`) 로 가서 404.
        // → DB url(`http://host:8183/api/mcp`) 을 scheme://authority 와 path 로 분리해
        //    baseUri = scheme://authority, sseEndpoint = path + "/sse" 로 설정한다.
        UrlSplit split = splitBaseAndSsePath(url);
        log.info("MCP server '{}' transport split: baseUri={}, sseEndpoint={}",
                serverId, split.baseUri, split.sseEndpoint);

        var httpTransport = HttpClientSseClientTransport.builder(split.baseUri)
                .sseEndpoint(split.sseEndpoint)
                .customizeClient(b -> b.connectTimeout(Duration.ofSeconds(30)))
                .build();
        this.client = McpClient.sync(httpTransport)
                .clientInfo(new McpSchema.Implementation("aimbase", "1.0.0"))
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * DB 에 저장된 단일 url 을 SDK 가 요구하는 (baseUri, sseEndpoint) 쌍으로 분리.
     *
     * 규칙:
     * - 입력에 path 가 없거나 "/" 만 있으면 baseUri=입력, sseEndpoint="/sse" (SDK 기본 동작과 동일)
     * - 입력에 path 가 있으면 baseUri=scheme://authority, sseEndpoint=path 끝의 "/sse" 보장
     *   - 이미 "/sse" 로 끝나면 그대로 사용
     *   - 끝이 "/" 면 "sse" 만 붙임
     *   - 그 외엔 "/sse" 를 append
     * - 잘못된 URL 이면 IllegalArgumentException
     *
     * package-private — 단위 테스트용.
     */
    static UrlSplit splitBaseAndSsePath(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid MCP server url: " + url, e);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("MCP server url must be absolute (scheme://host): " + url);
        }
        String authority = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return new UrlSplit(authority, "/sse");
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String sseEndpoint;
        if (trimmed.endsWith("/sse")) {
            sseEndpoint = trimmed;
        } else {
            sseEndpoint = trimmed + "/sse";
        }
        if (!sseEndpoint.startsWith("/")) {
            sseEndpoint = "/" + sseEndpoint;
        }
        return new UrlSplit(authority, sseEndpoint);
    }

    /** package-private — splitBaseAndSsePath 의 반환 타입. */
    record UrlSplit(String baseUri, String sseEndpoint) {}

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

    /**
     * MCP 서버에서 리소스 목록 조회.
     * @return 리소스 목록 (URI, name, description, mimeType)
     */
    public List<Map<String, Object>> listResources() {
        ensureConnected();
        try {
            McpSchema.ListResourcesResult result = client.listResources();
            return result.resources().stream()
                    .map(r -> {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("uri", r.uri());
                        m.put("name", r.name());
                        if (r.description() != null) m.put("description", r.description());
                        if (r.mimeType() != null) m.put("mimeType", r.mimeType());
                        m.put("serverId", serverId);
                        return m;
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("listResources failed for server '{}': {}", serverId, e.getMessage());
            return List.of();
        }
    }

    /**
     * MCP 서버에서 특정 리소스 읽기.
     * @param uri 리소스 URI
     * @return 콘텐츠 맵 { uri, mimeType, content, type }
     */
    public Map<String, Object> readResource(String uri) {
        ensureConnected();
        McpSchema.ReadResourceResult result = client.readResource(
                new McpSchema.ReadResourceRequest(uri));

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("uri", uri);

        if (result.contents() != null && !result.contents().isEmpty()) {
            var content = result.contents().get(0);
            if (content instanceof McpSchema.TextResourceContents textContent) {
                response.put("type", "text");
                response.put("mimeType", textContent.mimeType());
                response.put("content", textContent.text());
            } else if (content instanceof McpSchema.BlobResourceContents blobContent) {
                response.put("type", "blob");
                response.put("mimeType", blobContent.mimeType());
                response.put("content", blobContent.blob());
            }
        }
        return response;
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
