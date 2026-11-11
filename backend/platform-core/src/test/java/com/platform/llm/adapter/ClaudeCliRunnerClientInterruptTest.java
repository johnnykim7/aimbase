package com.platform.llm.adapter;

import com.platform.service.AgentEndpoint;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** CR-175 TC-CHT-021: 실행기 멈춤 알림. */
class ClaudeCliRunnerClientInterruptTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private AgentEndpoint serve(int status, String body, AtomicReference<String> received) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/interrupt", ex -> {
            received.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    + " key=" + ex.getRequestHeaders().getFirst("X-Api-Key"));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        return new AgentEndpoint("agent-1", "http://127.0.0.1:" + server.getAddress().getPort(), null);
    }

    @Test
    @DisplayName("세션 id 를 run_id 로 보내고 실행기 답을 돌려준다")
    void sendsRunIdAndReadsAnswer() throws IOException {
        AtomicReference<String> received = new AtomicReference<>();
        AgentEndpoint ep = serve(200, "{\"interrupted\":true}", received);

        assertThat(new ClaudeCliRunnerClient().interrupt(ep, "sess-1", "k1")).isTrue();
        assertThat(received.get()).contains("\"run_id\":\"sess-1\"").contains("key=k1");
    }

    @Test
    @DisplayName("이 주소를 모르는 이전 실행기(404)는 오류 없이 거짓")
    void oldRunnerIsIgnored() throws IOException {
        AgentEndpoint ep = serve(404, "{\"error\":\"Not Found\"}", new AtomicReference<>());

        assertThat(new ClaudeCliRunnerClient().interrupt(ep, "sess-1", "k1")).isFalse();
    }
}
