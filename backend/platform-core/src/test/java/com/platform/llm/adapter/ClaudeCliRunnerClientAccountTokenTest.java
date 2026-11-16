package com.platform.llm.adapter;

import com.platform.service.AgentEndpoint;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** CR-196 TC-TOK-021: api 가 실행기 토큰 API 를 부르는 모양. */
class ClaudeCliRunnerClientAccountTokenTest {

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> uri = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "{\"present\":true,\"managed_by\":\"tenant\"}";
    private AgentEndpoint endpoint;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/account-token", ex -> {
            method.set(ex.getRequestMethod());
            uri.set(ex.getRequestURI().toString());
            key.set(ex.getRequestHeaders().getFirst("X-Api-Key"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        endpoint = new AgentEndpoint("a1", "http://localhost:" + server.getAddress().getPort(), "hash");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void getEncodesFolderAndSendsKey() throws Exception {
        ClaudeCliRunnerClient client = new ClaudeCliRunnerClient();

        ClaudeCliRunnerClient.AccountTokenReply r = client.accountToken(endpoint, "/data/claude accounts/w1", "k1",
                Duration.ofSeconds(5));

        assertThat(r.ok()).isTrue();
        assertThat(r.body()).containsEntry("present", true);
        assertThat(method.get()).isEqualTo("GET");
        assertThat(uri.get()).isEqualTo("/v1/account-token?config_dir=%2Fdata%2Fclaude+accounts%2Fw1");
        assertThat(key.get()).isEqualTo("k1");

        client.accountToken(endpoint, "", null, Duration.ofSeconds(5));
        assertThat(uri.get()).isEqualTo("/v1/account-token");
        assertThat(key.get()).isNull();
    }

    @Test
    void putSendsFolderTokenByAndReadsErrorCode() throws Exception {
        ClaudeCliRunnerClient client = new ClaudeCliRunnerClient();
        status = 400;
        reply = "{\"error\":\"account_token_rejected\"}";

        ClaudeCliRunnerClient.AccountTokenReply r = client.putAccountToken(endpoint, "/data/w1",
                "sk-ant-oat01-abc", "platform", "k1");

        assertThat(method.get()).isEqualTo("PUT");
        assertThat(body.get()).contains("\"config_dir\":\"/data/w1\"").contains("\"token\":\"sk-ant-oat01-abc\"")
                .contains("\"by\":\"platform\"");
        assertThat(r.ok()).isFalse();
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.error()).isEqualTo("account_token_rejected");
    }
}
