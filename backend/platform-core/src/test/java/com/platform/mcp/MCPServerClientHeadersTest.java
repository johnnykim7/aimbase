package com.platform.mcp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-145 등록 머리글이 실제 요청에 실리는지 가짜 MCP 서버로 본다.
 * TC-MCPH-001(SSE), TC-MCPH-002(Streamable), TC-MCPH-003(머리글 없음), TC-MCPH-007(로그).
 */
@Timeout(60)
class MCPServerClientHeadersTest {

    private static final String KEY_HEADER = "X-API-Key";
    private static final String SECRET = "secret-cr145";
    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile OutputStream sseStream;

    private record Request(String method, String path, Headers headers) {}

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/sse-server/sse", this::handleSseStream);
        server.createContext("/sse-server/message", this::handleSseMessage);
        server.createContext("/stream-server/mcp", this::handleStreamable);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void record(HttpExchange exchange) {
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders()));
    }

    private void handleSseStream(HttpExchange exchange) throws IOException {
        record(exchange);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        sseStream = exchange.getResponseBody();
        writeSse("endpoint", "/sse-server/message?sessionId=s1");
    }

    private void handleSseMessage(HttpExchange exchange) throws IOException {
        record(exchange);
        Map<?, ?> message = JSON.readValue(exchange.getRequestBody(), Map.class);
        exchange.sendResponseHeaders(202, -1);
        exchange.close();
        if ("initialize".equals(message.get("method"))) {
            writeSse("message", initializeResult(message));
        }
    }

    private void handleStreamable(HttpExchange exchange) throws IOException {
        record(exchange);
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        Map<?, ?> message = JSON.readValue(exchange.getRequestBody(), Map.class);
        if (!"initialize".equals(message.get("method"))) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }
        byte[] body = initializeResult(message).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private synchronized void writeSse(String event, String data) throws IOException {
        sseStream.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        sseStream.flush();
    }

    private static String initializeResult(Map<?, ?> request) throws IOException {
        Map<?, ?> params = (Map<?, ?>) request.get("params");
        return JSON.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", request.get("id"),
                "result", Map.of(
                        "protocolVersion", params.get("protocolVersion"),
                        "capabilities", Map.of(),
                        "serverInfo", Map.of("name", "fake", "version", "1"))));
    }

    private List<Request> requestsTo(String method, String path) {
        return requests.stream().filter(r -> r.method().equals(method) && r.path().equals(path)).toList();
    }

    @Test
    void sse_sendsHeadersOnStreamAndMessages() {
        Map<String, Object> config = Map.of("url", baseUrl() + "/sse-server",
                "headers", Map.of(KEY_HEADER, SECRET));

        try (MCPServerClient client = new MCPServerClient("cr145-sse", "sse", config)) {
            client.connect();
        }

        List<Request> streams = requestsTo("GET", "/sse-server/sse");
        List<Request> messages = requestsTo("POST", "/sse-server/message");
        assertThat(streams).isNotEmpty();
        assertThat(messages).isNotEmpty();
        assertThat(streams).allSatisfy(r -> assertThat(r.headers().getFirst(KEY_HEADER)).isEqualTo(SECRET));
        assertThat(messages).allSatisfy(r -> assertThat(r.headers().getFirst(KEY_HEADER)).isEqualTo(SECRET));
    }

    @Test
    void streamable_sendsHeadersOnEveryRequest() {
        Map<String, Object> config = Map.of("url", baseUrl() + "/stream-server/mcp",
                "headers", Map.of(KEY_HEADER, SECRET));

        try (MCPServerClient client = new MCPServerClient("cr145-stream", "streamable", config)) {
            client.connect();
        }

        List<Request> posts = requestsTo("POST", "/stream-server/mcp");
        assertThat(posts).isNotEmpty();
        assertThat(requests).allSatisfy(r -> assertThat(r.headers().getFirst(KEY_HEADER)).isEqualTo(SECRET));
    }

    @Test
    void withoutHeaders_connectsAsBefore() {
        Map<String, Object> config = Map.of("url", baseUrl() + "/sse-server");

        try (MCPServerClient client = new MCPServerClient("cr145-plain", "sse", config)) {
            client.connect();
            assertThat(client.isInitialized()).isTrue();
        }

        assertThat(requestsTo("GET", "/sse-server/sse")).isNotEmpty();
        assertThat(requests).allSatisfy(r -> assertThat(r.headers().containsKey(KEY_HEADER)).isFalse());
    }

    @Test
    void logsHeaderNamesButNotValues() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            Map<String, Object> config = Map.of("url", baseUrl() + "/sse-server",
                    "headers", Map.of(KEY_HEADER, SECRET));
            try (MCPServerClient client = new MCPServerClient("cr145-log", "sse", config)) {
                client.connect();
            }
        } finally {
            root.detachAppender(appender);
        }

        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anyMatch(m -> m.contains(KEY_HEADER));
        assertThat(messages).noneMatch(m -> m.contains(SECRET));
    }
}
