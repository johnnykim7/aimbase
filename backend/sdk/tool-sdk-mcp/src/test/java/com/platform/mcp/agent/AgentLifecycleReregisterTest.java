package com.platform.mcp.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-156: 하트비트 주기의 재등록. 가짜 Aimbase 로 등록과 하트비트 응답을 정한다.
 */
class AgentLifecycleReregisterTest {

    private HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile int registerStatus;
    private volatile int heartbeatStatus;
    private volatile int nextId;
    private AgentLifecycle lifecycle;

    @BeforeEach
    void setUp() throws IOException {
        registerStatus = 201;
        heartbeatStatus = 200;
        nextId = 1;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            calls.add(exchange.getRequestMethod() + " " + path);
            int status;
            String body;
            if (path.endsWith("/register")) {
                status = registerStatus;
                body = status == 201 ? "{\"data\":{\"id\":\"agent-" + (nextId++) + "\"}}" : "{}";
            } else {
                status = heartbeatStatus;
                body = "{}";
            }
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        AgentConfig config = new AgentConfig("cli-runner-test",
                "http://127.0.0.1:" + server.getAddress().getPort(), "plat-test", 0);
        lifecycle = new AgentLifecycle(config, List.of());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("TC-AGR-004 시작 때 등록에 실패하면 다음 주기에 다시 등록하고 id 없이 하트비트를 보내지 않는다")
    void registersAgainAfterFailedStart() {
        registerStatus = 503;
        lifecycle.heartbeatTick();
        assertThat(lifecycle.getAgentId()).isNull();

        registerStatus = 201;
        lifecycle.heartbeatTick();

        assertThat(lifecycle.getAgentId()).isEqualTo("agent-1");
        assertThat(calls).containsExactly("POST /api/v1/agents/register", "POST /api/v1/agents/register");
        assertThat(calls).noneMatch(c -> c.contains("/null/"));
    }

    @Test
    @DisplayName("TC-AGR-005 하트비트가 404 면 같은 주기에 다시 등록하고 새 id 로 바꾼다")
    void registersAgainWhenAimbaseForgotTheAgent() {
        lifecycle.heartbeatTick();
        assertThat(lifecycle.getAgentId()).isEqualTo("agent-1");

        heartbeatStatus = 404;
        lifecycle.heartbeatTick();

        assertThat(lifecycle.getAgentId()).isEqualTo("agent-2");
        assertThat(calls).containsExactly(
                "POST /api/v1/agents/register",
                "POST /api/v1/agents/agent-1/heartbeat",
                "POST /api/v1/agents/register");
    }

    @Test
    @DisplayName("TC-AGR-006 하트비트가 500 이거나 닿지 않으면 다시 등록하지 않고 id 를 그대로 둔다")
    void keepsRegistrationWhenHeartbeatFails() {
        lifecycle.heartbeatTick();

        heartbeatStatus = 500;
        lifecycle.heartbeatTick();
        server.stop(0);
        lifecycle.heartbeatTick();

        assertThat(lifecycle.getAgentId()).isEqualTo("agent-1");
        assertThat(calls).containsExactly(
                "POST /api/v1/agents/register",
                "POST /api/v1/agents/agent-1/heartbeat");
    }
}
