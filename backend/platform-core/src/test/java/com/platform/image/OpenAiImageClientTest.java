package com.platform.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CR-184: 공급처 호출 모양과 실패 코드. 로컬 가짜 공급처로 본다. */
class OpenAiImageClientTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ImageConnection conn = new ImageConnection("img-1", "gpt-image-1", "medium", "sk-test");
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<byte[]> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply;
    private HttpServer server;
    private OpenAiImageClient client;

    @BeforeEach
    void setUp() throws IOException {
        reply = "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(PNG) + "\"}],"
                + "\"usage\":{\"input_tokens\":12,\"output_tokens\":345,\"total_tokens\":357}}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", ex -> {
            path.set(ex.getRequestURI().getPath());
            contentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            body.set(ex.getRequestBody().readAllBytes());
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        client = new OpenAiImageClient(objectMapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("TC-CHT-102 1 바탕_그림이_없으면_generations_JSON_으로_한_장을_부르고_그림과_토큰을_돌려준다")
    void create_calls_generations_json() throws Exception {
        HttpResponse<byte[]> resp = client.request(conn, "고양이", List.of(), Duration.ofSeconds(5)).get();
        OpenAiImageClient.Result result = client.toResult(resp);

        assertThat(path.get()).isEqualTo("/v1/images/generations");
        assertThat(contentType.get()).isEqualTo("application/json");
        assertThat(auth.get()).isEqualTo("Bearer sk-test");
        JsonNode json = objectMapper.readTree(body.get());
        assertThat(json.get("model").asText()).isEqualTo("gpt-image-1");
        assertThat(json.get("prompt").asText()).isEqualTo("고양이");
        assertThat(json.get("size").asText()).isEqualTo("auto");
        assertThat(json.get("quality").asText()).isEqualTo("medium");
        assertThat(json.get("n").asInt()).isEqualTo(1);
        assertThat(result.bytes()).isEqualTo(PNG);
        assertThat(result.inputTokens()).isEqualTo(12);
        assertThat(result.outputTokens()).isEqualTo(345);
    }

    @Test
    @DisplayName("TC-CHT-102 2 바탕_그림이_있으면_edits_multipart_에_image[]_를_장수만큼_싣는다")
    void edit_calls_edits_multipart() throws Exception {
        List<OpenAiImageClient.SourceImage> sources = List.of(
                new OpenAiImageClient.SourceImage(PNG, "image/png", "base.png"),
                new OpenAiImageClient.SourceImage(PNG, "image/png", "att.png"));

        client.toResult(client.request(conn, "모자 씌워", sources, Duration.ofSeconds(5)).get());

        assertThat(path.get()).isEqualTo("/v1/images/edits");
        assertThat(contentType.get()).startsWith("multipart/form-data; boundary=");
        String text = new String(body.get(), StandardCharsets.ISO_8859_1);
        assertThat(text.split("name=\"image\\[\\]\"", -1)).hasSize(3);
        assertThat(text).contains("name=\"model\"\r\n\r\ngpt-image-1\r\n")
                .contains("name=\"size\"\r\n\r\nauto\r\n")
                .contains("name=\"quality\"\r\n\r\nmedium\r\n")
                .contains("name=\"n\"\r\n\r\n1\r\n");
        String utf8 = new String(body.get(), StandardCharsets.UTF_8);
        assertThat(utf8).contains("name=\"prompt\"\r\n\r\n모자 씌워\r\n");
    }

    @Test
    @DisplayName("TC-CHT-103 1 401_과_403_은_image_provider_auth")
    void auth_errors() throws Exception {
        assertCode(401, "invalid_api_key", ImageProviderException.AUTH);
        assertCode(403, "unsupported_country_region_territory", ImageProviderException.AUTH);
    }

    @Test
    @DisplayName("TC-CHT-103 2 429_는_image_provider_quota")
    void quota_error() throws Exception {
        assertCode(429, "insufficient_quota", ImageProviderException.QUOTA);
    }

    @Test
    @DisplayName("TC-CHT-103 3 내용_거절_400_은_image_rejected_그_밖_400_과_500_은_image_failed")
    void rejected_and_other_errors() throws Exception {
        assertCode(400, "moderation_blocked", ImageProviderException.REJECTED);
        assertCode(400, "content_policy_violation", ImageProviderException.REJECTED);
        assertCode(400, "invalid_value", ImageProviderException.FAILED);
        assertCode(500, "server_error", ImageProviderException.FAILED);
    }

    @Test
    @DisplayName("TC-CHT-103 4 성공_응답에_그림이_없으면_image_failed")
    void success_without_image() throws Exception {
        reply = "{\"data\":[]}";
        HttpResponse<byte[]> resp = client.request(conn, "x", List.of(), Duration.ofSeconds(5)).get();

        assertThatThrownBy(() -> client.toResult(resp))
                .isInstanceOf(ImageProviderException.class)
                .extracting("code").isEqualTo(ImageProviderException.FAILED);
    }

    private void assertCode(int httpStatus, String providerCode, String expected) throws Exception {
        status = httpStatus;
        reply = "{\"error\":{\"message\":\"m\",\"type\":\"t\",\"param\":null,\"code\":\"" + providerCode + "\"}}";
        HttpResponse<byte[]> resp = client.request(conn, "x", List.of(), Duration.ofSeconds(5)).get();

        assertThatThrownBy(() -> client.toResult(resp))
                .isInstanceOf(ImageProviderException.class)
                .extracting("code").isEqualTo(expected);
    }
}
