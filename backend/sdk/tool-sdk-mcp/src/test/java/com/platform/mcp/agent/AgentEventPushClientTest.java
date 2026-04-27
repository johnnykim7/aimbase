package com.platform.mcp.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-070 Phase B: AgentEventPushClient 단위 테스트.
 *
 * 가짜 HTTP 서버(jdk.httpserver) 를 띄워서 푸시된 페이로드를 검증.
 * 외부 모킹 라이브러리 의존 없음.
 */
class AgentEventPushClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private int port;
    private List<String> capturedBodies;
    private AtomicReference<String> capturedAuthHeader;
    private AtomicReference<String> capturedPath;
    private int responseStatus;

    @BeforeEach
    void setUp() throws IOException {
        capturedBodies = new ArrayList<>();
        capturedAuthHeader = new AtomicReference<>();
        capturedPath = new AtomicReference<>();
        responseStatus = 202;

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            capturedBodies.add(new String(body, StandardCharsets.UTF_8));
            capturedAuthHeader.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
            capturedPath.set(exchange.getRequestURI().getPath());
            byte[] resp = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void pushTextDelta_sends_correct_payload() throws Exception {
        AgentEventPushClient client = new AgentEventPushClient(
                "http://127.0.0.1:" + port, "test-key",
                "agent-uuid", "run-abc");

        boolean ok = client.pushTextDelta("hello world");

        assertThat(ok).isTrue();
        assertThat(capturedAuthHeader.get()).isEqualTo("test-key");
        assertThat(capturedPath.get()).isEqualTo("/api/v1/agents/agent-uuid/runs/run-abc/events");
        assertThat(capturedBodies).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = MAPPER.readValue(capturedBodies.get(0), Map.class);
        assertThat(body).containsEntry("type", "text_delta");
        assertThat(body).containsEntry("delta", "hello world");
    }

    @Test
    void pushToolUseStart_sends_id_name_input() throws Exception {
        AgentEventPushClient client = new AgentEventPushClient(
                "http://127.0.0.1:" + port, "test-key",
                "agent-uuid", "run-abc");

        boolean ok = client.pushToolUseStart("toolu_01", "Read",
                Map.of("file_path", "/tmp/x"));

        assertThat(ok).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = MAPPER.readValue(capturedBodies.get(0), Map.class);
        assertThat(body).containsEntry("type", "tool_use_start");
        assertThat(body).containsEntry("id", "toolu_01");
        assertThat(body).containsEntry("name", "Read");
        assertThat(body.get("input")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> inputMap = (Map<String, Object>) body.get("input");
        assertThat(inputMap).containsEntry("file_path", "/tmp/x");
    }

    @Test
    void pushToolResult_sends_id_output_isError() throws Exception {
        AgentEventPushClient client = new AgentEventPushClient(
                "http://127.0.0.1:" + port, "test-key",
                "agent-uuid", "run-abc");

        boolean ok = client.pushToolResult("toolu_01", "file contents", false);

        assertThat(ok).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = MAPPER.readValue(capturedBodies.get(0), Map.class);
        assertThat(body).containsEntry("type", "tool_result");
        assertThat(body).containsEntry("tool_use_id", "toolu_01");
        assertThat(body).containsEntry("output", "file contents");
        assertThat(body).containsEntry("is_error", false);
    }

    @Test
    void server_5xx_returns_false_no_throw() {
        responseStatus = 500;
        AgentEventPushClient client = new AgentEventPushClient(
                "http://127.0.0.1:" + port, "test-key",
                "agent-uuid", "run-abc");

        boolean ok = client.pushTextDelta("x");

        assertThat(ok).isFalse();
    }

    @Test
    void unreachable_server_returns_false_no_throw() {
        // 닫힌 포트
        AgentEventPushClient client = new AgentEventPushClient(
                "http://127.0.0.1:1", "test-key",
                "agent-uuid", "run-abc");

        boolean ok = client.pushTextDelta("x");

        assertThat(ok).isFalse();
    }

    @Test
    void trailing_slash_in_baseUrl_is_normalized() throws Exception {
        AgentEventPushClient client = new AgentEventPushClient(
                "http://127.0.0.1:" + port + "/", "test-key",
                "agent-uuid", "run-abc");

        client.pushTextDelta("hi");

        assertThat(capturedPath.get()).isEqualTo("/api/v1/agents/agent-uuid/runs/run-abc/events");
    }
}
