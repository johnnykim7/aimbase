package com.platform.clitoken;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** CR-196 TC-TOK-013, 014: bp-notification 발송 API 를 부르는 모양과 실패 판정. */
class CliTokenNotifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Object> VARS = Map.of("runner", "runner-a", "account", "work01",
            "expiresOn", "2026-11-23", "daysLeft", 7);

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final List<Map<?, ?>> bodies = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/messages", ex -> {
            paths.add(ex.getRequestURI().getPath());
            keys.add(ex.getRequestHeaders().getFirst("X-API-Key"));
            bodies.add(MAPPER.readValue(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    Map.class));
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsEmailAndSmsInBpNotificationShape() {
        CliTokenNotifier notifier = new CliTokenNotifier(baseUrl, "k-1", Duration.ofSeconds(5));

        assertThat(notifier.sendEmail("AIMBASE_CLI_TOKEN_EXPIRY_EMAIL", VARS, "a@x.com")).isTrue();
        assertThat(notifier.sendSms("AIMBASE_CLI_TOKEN_EXPIRY_SMS", VARS, "01012345678")).isTrue();

        assertThat(paths).containsExactly("/api/v1/messages/email", "/api/v1/messages/sms");
        assertThat(keys).containsExactly("k-1", "k-1");
        assertThat(bodies.get(0)).isEqualTo(Map.of("templateCode", "AIMBASE_CLI_TOKEN_EXPIRY_EMAIL",
                "variables", VARS, "email", "a@x.com"));
        assertThat(bodies.get(1)).isEqualTo(Map.of("templateCode", "AIMBASE_CLI_TOKEN_EXPIRY_SMS",
                "variables", VARS, "phoneNumber", "01012345678"));
    }

    @Test
    void missingKeyOrFailedReplyCountsAsNotSent() {
        assertThat(new CliTokenNotifier(baseUrl, "", Duration.ofSeconds(5))
                .sendEmail("T", VARS, "a@x.com")).isFalse();
        assertThat(paths).isEmpty();

        status = 500;
        assertThat(new CliTokenNotifier(baseUrl, "k-1", Duration.ofSeconds(5))
                .sendSms("T", VARS, "01012345678")).isFalse();

        assertThat(new CliTokenNotifier("http://127.0.0.1:1", "k-1", Duration.ofSeconds(2))
                .sendEmail("T", VARS, "a@x.com")).isFalse();
    }
}
