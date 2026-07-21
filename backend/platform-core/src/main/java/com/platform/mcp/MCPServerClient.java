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
    /** CR-124: true 면 Streamable HTTP, false 면 기존 SSE. */
    private final boolean streamable;
    // SSE 세션 무효화(사이드카 재기동 등) 시 reconnect 로 교체하므로 final 아님.
    private McpSyncClient client;
    private boolean initialized = false;

    /**
     * 세션 생명주기 보호 — ReadWriteLock.
     *
     * <p>원래 문제: 단일 공유 {@link #client}(SSE 세션 1개)를 여러 스레드가 동시에 쓰는데,
     * 한 스레드의 reconnect(close+교체)가 진행 중인 다른 스레드의 세션을 무효화
     * ("MCP session with server terminated" → 상호 reconnect race).
     *
     * <p>해법: 정상 호출(callTool/discoverTools/list·readResource/connect)은 <b>read 락</b>으로
     * 서로 동시 허용한다(SSE 세션은 동시 요청 다중화 지원 — 사이드카 렌더 N개 병렬 유지).
     * 세션을 교체하는 reconnect/close 만 <b>write 락</b>으로 배타 실행한다. 정상 호출 중에는 reconnect 가
     * write 락을 못 잡아 "호출 중 세션 교체"가 불가능 → race 제거. 동시에 정상 호출끼리는 직렬화하지
     * 않아 throughput 보존(전면 직렬화의 부작용 — 무거운 렌더 1건이 뒤를 막던 문제 — 해소).
     */
    private final java.util.concurrent.locks.ReadWriteLock sessionLock =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    // reconnect 시 동일 파라미터로 client 를 재생성하기 위해 보존.
    private final UrlSplit urlSplit;
    private final int requestTimeoutSeconds;

    /** 기본 requestTimeout 30초. 문서 파싱처럼 오래 걸리는 호출은 아래 오버로드로 상향한다. */
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 30;

    public MCPServerClient(String serverId, String transport, Map<String, Object> config) {
        this(serverId, transport, config, DEFAULT_REQUEST_TIMEOUT_SECONDS);
    }

    /**
     * requestTimeout 을 외부에서 주입하는 생성자.
     * PDF 다운로드+파싱처럼 오래 걸리는 사이드카 호출(parse_document 등)은 30초로 부족하므로
     * 호출부(MCPRagClient)에서 더 긴 값을 넘긴다. connect/initialization 은 30초 유지(연결·핸드셰이크는 빨라야 함).
     */
    public MCPServerClient(String serverId, String transport, Map<String, Object> config, int requestTimeoutSeconds) {
        this.serverId = serverId;

        String url = (String) config.get("url");
        if (url == null) {
            throw new IllegalArgumentException("MCP server config must contain 'url' for transport: " + transport);
        }

        // CR-124: "streamable"/"streamable-http" 는 Streamable HTTP 클라이언트로 붙는다.
        // aimbase agent 는 SDK 2.0.0 전환으로 /mcp(Streamable) 만 노출하므로 이 경로가 필요하다.
        // Python 사이드카(rag/safety/evaluation)는 여전히 SSE 라 기존 경로를 유지한다.
        this.streamable = "streamable".equalsIgnoreCase(transport)
                || "streamable-http".equalsIgnoreCase(transport);

        // SDK 0.10.0 의 HttpClientSseClientTransport 는 baseUri + sseEndpoint("/sse" 기본)
        // 형태로 호출. Java URI.resolve 는 절대경로(sseEndpoint) 가 base path 를 덮어쓰므로,
        // baseUri 에 context path(`/api/mcp`) 가 포함돼 있으면 호출이 root(`/sse`) 로 가서 404.
        // → DB url(`http://host:8183/api/mcp`) 을 scheme://authority 와 path 로 분리해
        //    baseUri = scheme://authority, sseEndpoint = path + "/sse" 로 설정한다.
        // CR-124: Streamable 은 "/sse" 를 붙이면 안 된다(단일 엔드포인트를 그대로 사용).
        UrlSplit split = this.streamable ? splitBaseAndPath(url) : splitBaseAndSsePath(url);
        log.info("MCP server '{}' transport split: baseUri={}, endpoint={}, streamable={}",
                serverId, split.baseUri, split.sseEndpoint, this.streamable);

        this.urlSplit = split;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.client = buildClient();
    }

    /**
     * transport + sync client 생성. 생성자와 {@link #reconnect()} 가 공용으로 사용한다.
     * 보존된 urlSplit/requestTimeoutSeconds 로 동일 구성을 재현한다.
     */
    private McpSyncClient buildClient() {
        // CR-124: Streamable 은 단일 엔드포인트(하위경로 없음)라 baseUri+endpoint 로 그대로 붙인다.
        io.modelcontextprotocol.spec.McpClientTransport httpTransport = streamable
                ? io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
                        .builder(urlSplit.baseUri())
                        .endpoint(urlSplit.sseEndpoint())
                        .customizeClient(b -> b.connectTimeout(Duration.ofSeconds(30)))
                        .build()
                : HttpClientSseClientTransport.builder(urlSplit.baseUri())
                        .sseEndpoint(urlSplit.sseEndpoint())
                        .customizeClient(b -> b.connectTimeout(Duration.ofSeconds(30)))
                        .build();
        return McpClient.sync(httpTransport)
                .clientInfo(new McpSchema.Implementation("aimbase", "1.0.0"))
                .requestTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                .initializationTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * SSE 세션 무효화(사이드카 재기동 등) 시 client 를 닫고 새로 만들어 재초기화.
     * 죽은 session_id 로 보낸 POST 가 404("Could not find session") 를 받아도 SDK 가 이를
     * 응답으로 전파하지 못해 requestTimeout 풀타임아웃이 나는 문제를 해소한다.
     */
    public void reconnect() {
        // 세션 교체 = write 락(배타). 진행 중인 read 호출이 다 빠질 때까지 대기 후 교체.
        sessionLock.writeLock().lock();
        try {
            try {
                if (client != null) client.close();
            } catch (Exception e) {
                log.warn("MCP server '{}' reconnect: error closing stale client: {}", serverId, e.getMessage());
            }
            this.client = buildClient();
            this.initialized = false;
            connectLocked();
            log.info("MCP server '{}' reconnected (new SSE session)", serverId);
        } finally {
            sessionLock.writeLock().unlock();
        }
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

    /**
     * CR-124: Streamable HTTP 용 분리 — {@code "/sse"} 를 붙이지 않고 경로를 그대로 보존한다.
     *
     * <p>Streamable 은 하위경로 없는 단일 엔드포인트(예: {@code /mcp})로 동작하므로
     * SSE 규칙(경로 끝에 {@code /sse} 보장)을 적용하면 존재하지 않는 경로로 붙게 된다.</p>
     *
     * package-private — 단위 테스트용.
     */
    static UrlSplit splitBaseAndPath(String url) {
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
            return new UrlSplit(authority, "/mcp");
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        return new UrlSplit(authority, trimmed);
    }

    /** package-private — splitBaseAndSsePath 의 반환 타입. */
    record UrlSplit(String baseUri, String sseEndpoint) {}

    /**
     * MCP 서버 초기화 핸드쉐이크 수행.
     * listTools(), callTool() 호출 전에 반드시 호출해야 함.
     */
    public void connect() {
        // 초기화는 initialized 플래그를 쓰므로 write 락(배타). 한 번만 일어나고 드물다.
        sessionLock.writeLock().lock();
        try {
            connectLocked();
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    /** write 락(connect/reconnect)을 이미 보유한 상태에서 호출되는 초기화 본체. */
    private void connectLocked() {
        if (!initialized) {
            McpSchema.InitializeResult result = client.initialize();
            initialized = true;
            log.info("Connected to MCP server '{}' — serverInfo={}, protocol={}",
                    serverId,
                    result.serverInfo() != null ? result.serverInfo().name() : "unknown",
                    result.protocolVersion());
        }
    }

    /** 호출 진입 시 초기화 보장 — 미초기화면 write 락으로 connect. 이미 초기화면 즉시 통과(락 없음). */
    private void ensureConnected() {
        if (!initialized) {
            connect();
        }
    }

    /**
     * MCP 서버에서 사용 가능한 도구 목록 조회.
     * @return UnifiedToolDef 목록 (ToolRegistry에 등록 가능한 형태)
     */
    public List<UnifiedToolDef> discoverTools() {
        ensureConnected();
        sessionLock.readLock().lock();
        try {
            List<McpSchema.Tool> mcpTools = client.listTools().tools();
            log.info("Discovered {} tool(s) from MCP server '{}'", mcpTools.size(), serverId);
            return mcpTools.stream()
                    .map(this::toUnifiedToolDef)
                    .toList();
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    /**
     * MCP 서버에서 특정 도구 실행.
     * @param toolName 도구 이름
     * @param input    도구 입력 파라미터
     * @return 실행 결과 문자열
     */
    public String callTool(String toolName, Map<String, Object> input) {
        ensureConnected();
        McpSchema.CallToolResult result;
        try {
            // 정상 경로 = read 락(동시 호출 허용, 서로 안 막음 — 렌더 N개 병렬 유지).
            result = callToolRead(toolName, input);
        } catch (Exception e) {
            // 죽은 SSE 세션(사이드카 재기동 등)에 걸리면 requestTimeout 풀타임아웃/IO 예외가 난다.
            // ★read 락을 잡은 채 reconnect(write 락)를 호출하면 업그레이드 deadlock → 반드시 read 락이
            //   풀린 상태에서 reconnect 한다(callToolRead 의 finally 가 read 락 해제 후 여기 도달).
            //   reconnect 는 write 락이라, 진행 중인 다른 read 호출이 다 빠질 때까지 기다려 race 없이 교체.
            log.warn("MCP tool '{}' call failed ({}), reconnecting and retrying once", toolName, e.getMessage());
            reconnect();
            result = callToolRead(toolName, input);
        }

        if (Boolean.TRUE.equals(result.isError())) {
            log.warn("MCP tool '{}' returned an error result", toolName);
        }

        return result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }

    /** read 락 하에 단일 callTool 실행. 락은 호출 동안만 잡고 finally 에서 해제(reconnect 업그레이드 회피). */
    private McpSchema.CallToolResult callToolRead(String toolName, Map<String, Object> input) {
        sessionLock.readLock().lock();
        try {
            return client.callTool(new McpSchema.CallToolRequest(toolName, input));
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    /**
     * MCP 서버에서 리소스 목록 조회.
     * @return 리소스 목록 (URI, name, description, mimeType)
     */
    public List<Map<String, Object>> listResources() {
        ensureConnected();
        sessionLock.readLock().lock();
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
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    /**
     * MCP 서버에서 특정 리소스 읽기.
     * @param uri 리소스 URI
     * @return 콘텐츠 맵 { uri, mimeType, content, type }
     */
    public Map<String, Object> readResource(String uri) {
        ensureConnected();
        sessionLock.readLock().lock();
        try {
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
        } finally {
            sessionLock.readLock().unlock();
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() {
        // 세션 종료 = write 락(배타). 진행 중 read 호출이 다 빠진 뒤 닫는다.
        sessionLock.writeLock().lock();
        try {
            client.close();
            initialized = false;
            log.info("Disconnected from MCP server '{}'", serverId);
        } catch (Exception e) {
            log.warn("Error closing MCP client for server '{}': {}", serverId, e.getMessage());
        } finally {
            sessionLock.writeLock().unlock();
        }
    }

    private UnifiedToolDef toUnifiedToolDef(McpSchema.Tool tool) {
        Map<String, Object> schema = buildSchema(tool.inputSchema());
        return new UnifiedToolDef(tool.name(), tool.description(), schema);
    }

    /**
     * CR-124 (SDK 2.0.0): {@code Tool.inputSchema()} 게터 반환 타입이
     * {@code McpSchema.JsonSchema} → {@code Map<String,Object>} 로 바뀌었다.
     * 원격 서버가 준 스키마 Map 을 그대로 신뢰하지 않고 필요한 키만 정규화해 옮긴다.
     */
    private Map<String, Object> buildSchema(Map<String, Object> jsonSchema) {
        if (jsonSchema == null || jsonSchema.isEmpty()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (jsonSchema.get("type") != null) result.put("type", jsonSchema.get("type"));
        if (jsonSchema.get("properties") != null) result.put("properties", jsonSchema.get("properties"));
        if (jsonSchema.get("required") != null) result.put("required", jsonSchema.get("required"));
        if (jsonSchema.get("additionalProperties") != null)
            result.put("additionalProperties", jsonSchema.get("additionalProperties"));
        if (result.isEmpty()) {
            result.put("type", "object");
            result.put("properties", Map.of());
        }
        return result;
    }
}
