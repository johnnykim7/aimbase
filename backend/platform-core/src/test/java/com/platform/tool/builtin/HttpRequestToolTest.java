package com.platform.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.platform.domain.ConnectionEntity;
import com.platform.repository.ConnectionRepository;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolResult;
import com.platform.tool.ValidationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-054: HttpRequestTool 단위 테스트.
 * WireMock으로 실제 HTTP 라운드트립 검증 — 200/404/5xx/timeout/인증주입/마스킹/JSON파싱/비JSON.
 */
class HttpRequestToolTest {

    private WireMockServer wireMock;
    private ConnectionRepository connectionRepository;
    private HttpRequestTool tool;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        baseUrl = "http://localhost:" + wireMock.port();

        connectionRepository = mock(ConnectionRepository.class);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        tool = new HttpRequestTool(connectionRepository, new ObjectMapper(), client);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private ConnectionEntity noneAuthConnection(String id) {
        return buildConnection(id, "HTTP", Map.of(
                "baseUrl", baseUrl,
                "auth", Map.of("type", "NONE")
        ));
    }

    private ConnectionEntity buildConnection(String id, String type, Map<String, Object> config) {
        ConnectionEntity e = new ConnectionEntity();
        e.setId(id);
        e.setName(id);
        e.setAdapter("http");
        e.setType(type);
        e.setConfig(new LinkedHashMap<>(config));
        return e;
    }

    private void stubConnection(ConnectionEntity entity) {
        when(connectionRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
    }

    // ── T1: GET 200 + JSON 파싱 ──
    @Test
    void get_200_parses_json_body() {
        stubConnection(noneAuthConnection("c1"));
        wireMock.stubFor(get(urlEqualTo("/ping?x=1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"value\":42}")));

        ToolResult result = tool.execute(Map.of(
                "connection_id", "c1",
                "method", "GET",
                "path", "/ping",
                "query", Map.of("x", "1")
        ), ToolContext.minimal(null, null));

        assertThat(result.success()).isTrue();
        Map<?, ?> out = (Map<?, ?>) result.output();
        assertThat(out.get("status")).isEqualTo(200);
        assertThat(out.get("bodyRaw")).isEqualTo(false);
        Map<?, ?> body = (Map<?, ?>) out.get("body");
        assertThat(body.get("ok")).isEqualTo(true);
        assertThat(body.get("value")).isEqualTo(42);
    }

    // ── T2: POST 201 + body 직렬화 ──
    @Test
    void post_201_serializes_json_body() {
        stubConnection(noneAuthConnection("c2"));
        wireMock.stubFor(post(urlPathEqualTo("/items"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.name", equalTo("x")))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"abc\"}")));

        ToolResult result = tool.execute(Map.of(
                "connection_id", "c2",
                "method", "POST",
                "path", "/items",
                "body", Map.of("name", "x", "tags", List.of("a", "b"))
        ), ToolContext.minimal(null, null));

        assertThat(result.success()).isTrue();
        Map<?, ?> out = (Map<?, ?>) result.output();
        assertThat(out.get("status")).isEqualTo(201);
    }

    // ── T3: 404 → status=404, success=false, 예외 없음 ──
    @Test
    void get_404_returns_status_without_exception() {
        stubConnection(noneAuthConnection("c3"));
        wireMock.stubFor(get(urlEqualTo("/missing"))
                .willReturn(aResponse().withStatus(404).withBody("not found")));

        ToolResult result = tool.execute(Map.of(
                "connection_id", "c3",
                "method", "GET",
                "path", "/missing"
        ), ToolContext.minimal(null, null));

        assertThat(result.success()).isFalse();
        Map<?, ?> out = (Map<?, ?>) result.output();
        assertThat(out.get("status")).isEqualTo(404);
        assertThat(out.get("error")).isNull();
    }

    // ── T4: 5xx → status=500, 예외 없음 ──
    @Test
    void get_500_returns_status_without_exception() {
        stubConnection(noneAuthConnection("c4"));
        wireMock.stubFor(get(urlEqualTo("/err"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"boom\"}")));

        ToolResult result = tool.execute(Map.of(
                "connection_id", "c4",
                "method", "GET",
                "path", "/err"
        ), ToolContext.minimal(null, null));

        assertThat(result.success()).isFalse();
        Map<?, ?> out = (Map<?, ?>) result.output();
        assertThat(out.get("status")).isEqualTo(500);
        Map<?, ?> body = (Map<?, ?>) out.get("body");
        assertThat(body.get("error")).isEqualTo("boom");
    }

    // ── T5: Connection 미존재 ──
    @Test
    void missing_connection_returns_error() {
        when(connectionRepository.findById("ghost")).thenReturn(Optional.empty());

        ToolResult result = tool.execute(Map.of(
                "connection_id", "ghost",
                "method", "GET",
                "path", "/x"
        ), ToolContext.minimal(null, null));

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("Connection not found");
    }

    // ── T6: Connection 타입 ≠ HTTP ──
    @Test
    void wrong_connection_type_returns_error() {
        stubConnection(buildConnection("search", "SEARCH", Map.of("baseUrl", baseUrl)));

        ToolResult result = tool.execute(Map.of(
                "connection_id", "search",
                "method", "GET",
                "path", "/x"
        ), ToolContext.minimal(null, null));

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("Connection type must be HTTP");
    }

    // ── T7: 인증 API_KEY(header) 자동 주입 ──
    @Test
    void api_key_header_is_injected() {
        stubConnection(buildConnection("fg", "HTTP", Map.of(
                "baseUrl", baseUrl,
                "auth", Map.of("type", "API_KEY", "in", "header", "name", "X-Api-Key", "value", "secret-123")
        )));
        wireMock.stubFor(get(urlEqualTo("/hello"))
                .withHeader("X-Api-Key", equalTo("secret-123"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        ToolResult result = tool.execute(Map.of(
                "connection_id", "fg",
                "method", "GET",
                "path", "/hello"
        ), ToolContext.minimal(null, null));

        assertThat(result.success()).isTrue();
        wireMock.verify(1, getRequestedFor(urlEqualTo("/hello")));
    }

    // ── T8: BEARER 인증 자동 주입 ──
    @Test
    void bearer_auth_is_injected() {
        stubConnection(buildConnection("bearer", "HTTP", Map.of(
                "baseUrl", baseUrl,
                "auth", Map.of("type", "BEARER", "value", "tok-xyz")
        )));
        wireMock.stubFor(get(urlEqualTo("/me"))
                .withHeader("Authorization", equalTo("Bearer tok-xyz"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"user\":\"me\"}")));

        ToolResult result = tool.execute(Map.of(
                "connection_id", "bearer",
                "method", "GET",
                "path", "/me"
        ), ToolContext.minimal(null, null));

        assertThat(result.success()).isTrue();
    }

    // ── T9: 비-JSON 응답 → bodyRaw=true, body=string ──
    @Test
    void non_json_response_marked_bodyRaw() {
        stubConnection(noneAuthConnection("c9"));
        wireMock.stubFor(get(urlEqualTo("/html"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<html>hi</html>")));

        ToolResult result = tool.execute(Map.of(
                "connection_id", "c9",
                "method", "GET",
                "path", "/html"
        ), ToolContext.minimal(null, null));

        Map<?, ?> out = (Map<?, ?>) result.output();
        assertThat(out.get("bodyRaw")).isEqualTo(true);
        assertThat(out.get("body")).isEqualTo("<html>hi</html>");
    }

    // ── T10: Read timeout → status=null + error 메시지 ──
    @Test
    void read_timeout_returns_null_status_with_error() {
        stubConnection(noneAuthConnection("c10"));
        wireMock.stubFor(get(urlEqualTo("/slow"))
                .willReturn(aResponse().withFixedDelay(2000).withStatus(200).withBody("ok")));

        ToolResult result = tool.execute(Map.of(
                "connection_id", "c10",
                "method", "GET",
                "path", "/slow",
                "timeout_ms", 200
        ), ToolContext.minimal(null, null));

        assertThat(result.success()).isFalse();
        Map<?, ?> out = (Map<?, ?>) result.output();
        assertThat(out.get("status")).isNull();
        Map<?, ?> error = (Map<?, ?>) out.get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("kind")).isIn("timeout", "io_error");
    }

    // ── T11: 감사 payload의 인증 헤더 마스킹 ──
    @Test
    void audit_payload_masks_auth_headers() {
        stubConnection(buildConnection("mask", "HTTP", Map.of(
                "baseUrl", baseUrl,
                "auth", Map.of("type", "API_KEY", "name", "X-Api-Key", "value", "secret")
        )));
        wireMock.stubFor(get(urlEqualTo("/z"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        ToolResult result = tool.execute(Map.of(
                "connection_id", "mask",
                "method", "GET",
                "path", "/z"
        ), ToolContext.minimal(null, null));

        Map<String, Object> audit = result.auditPayload();
        Map<?, ?> reqHeaders = (Map<?, ?>) audit.get("requestHeaders");
        assertThat(reqHeaders.get("X-Api-Key")).isEqualTo("***");
    }

    // ── T12: 입력 검증 — connection_id 누락 ──
    @Test
    void validateInput_missing_connection_id() {
        ValidationResult v = tool.validateInput(Map.of("method", "GET", "path", "/x"),
                ToolContext.minimal(null, null));
        assertThat(v.valid()).isFalse();
        assertThat(v.message()).contains("connection_id");
    }

    // ── T13: 입력 검증 — 허용 method ──
    @Test
    void validateInput_rejects_unknown_method() {
        ValidationResult v = tool.validateInput(Map.of(
                "connection_id", "x",
                "method", "OPTIONS",
                "path", "/x"
        ), ToolContext.minimal(null, null));
        assertThat(v.valid()).isFalse();
        assertThat(v.message()).contains("method");
    }
}
