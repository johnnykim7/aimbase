package com.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.ConnectionEntity;
import com.platform.repository.ConnectionRepository;
import com.platform.speech.SpeechService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * CR-165: 음성 합성 창구가 OpenAI 로 보내는 본문과, 키를 찾는 연결 모양.
 *
 * <p>OpenAI 대신 JVM 안의 HTTP 서버가 받은 본문과 머리값을 본다.
 */
@ExtendWith(MockitoExtension.class)
class SpeechControllerTtsTest {

    private static final byte[] FAKE_MP3 = {(byte) 0xFF, (byte) 0xF3, 0x01, 0x02};

    @Mock private ConnectionRepository connectionRepository;
    @Mock private SpeechService speechService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer fakeOpenAi;
    private final List<String> receivedBodies = new ArrayList<>();
    private final List<String> receivedAuth = new ArrayList<>();
    private int upstreamStatus = 200;
    private SpeechController controller;

    @BeforeEach
    void setUp() throws IOException {
        fakeOpenAi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeOpenAi.createContext("/v1/audio/speech", exchange -> {
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] out = upstreamStatus == 200 ? FAKE_MP3 : "{\"error\":\"bad\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(upstreamStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        fakeOpenAi.start();
        String url = "http://127.0.0.1:" + fakeOpenAi.getAddress().getPort() + "/v1/audio/speech";
        controller = new SpeechController(connectionRepository, speechService, objectMapper, url);
    }

    @AfterEach
    void tearDown() {
        fakeOpenAi.stop(0);
    }

    @Test
    void sendsTextWithBackslashTabCarriageReturnAndQuotesAsValidJson() throws Exception {
        givenConnections(adminScreenConnection("conn-1", "connected", Map.of("apiKey", "k-admin")));
        String text = "경로 C:\\temp\\보고서\t탭\r\n둘째 줄 \"큰따옴표\" '작은따옴표' 끝 역슬래시\\"
                + " 제어문자\u0001 유니코드 \u2028 줄분리";

        ResponseEntity<byte[]> res = controller.textToSpeech(
                new SpeechController.TtsRequest(text, null, null, null));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getContentType()).hasToString("audio/mpeg");
        assertThat(res.getBody()).isEqualTo(FAKE_MP3);
        JsonNode sent = objectMapper.readTree(receivedBodies.get(0));
        assertThat(sent.get("input").asText()).isEqualTo(text);
        assertThat(sent.get("model").asText()).isEqualTo("tts-1");
        assertThat(sent.get("voice").asText()).isEqualTo("alloy");
        assertThat(sent.get("speed").asDouble()).isEqualTo(1.0);
    }

    @Test
    void sendsModelVoiceAndSpeedAsGiven() throws Exception {
        givenConnections(adminScreenConnection("conn-1", "connected", Map.of("apiKey", "k-admin")));

        controller.textToSpeech(new SpeechController.TtsRequest("안녕하세요", "tts-1-hd", "no\"va", 1.25));

        JsonNode sent = objectMapper.readTree(receivedBodies.get(0));
        assertThat(sent.get("model").asText()).isEqualTo("tts-1-hd");
        assertThat(sent.get("voice").asText()).isEqualTo("no\"va");
        assertThat(sent.get("speed").asDouble()).isEqualTo(1.25);
    }

    @Test
    void usesKeyOfOpenAiConnectionMadeByAdminScreen() {
        givenConnections(adminScreenConnection("conn-1", "connected", Map.of("apiKey", "k-admin")));

        ResponseEntity<byte[]> res = controller.textToSpeech(new SpeechController.TtsRequest("안녕", null, null, null));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(receivedAuth).containsExactly("Bearer k-admin");
    }

    @Test
    void readsKeyFromEnvironmentVariableNamedInConnection() {
        String envName = "HOME";
        String envValue = System.getenv(envName);
        assertThat(envValue).isNotBlank();
        givenConnections(adminScreenConnection("conn-1", "connected", Map.of("apiKey_env", envName)));

        ResponseEntity<byte[]> res = controller.textToSpeech(new SpeechController.TtsRequest("안녕", null, null, null));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(receivedAuth).containsExactly("Bearer " + envValue);
    }

    @Test
    void keepsFirstConnectionShapeAheadOfAdminScreenShape() {
        ConnectionEntity legacy = connection("legacy-1", "openai", "openai", "connected", Map.of("api_key", "k-legacy"));
        givenConnections(adminScreenConnection("conn-1", "connected", Map.of("apiKey", "k-admin")), legacy);

        controller.textToSpeech(new SpeechController.TtsRequest("안녕", null, null, null));

        assertThat(receivedAuth).containsExactly("Bearer k-legacy");
    }

    @Test
    void returns503WhenOnlyOpenAiCompatibleOrDisconnectedConnectionsExist() {
        givenConnections(
                connection("qwen-1", "llm", "openai_compatible", "connected", Map.of("apiKey", "k-qwen")),
                connection("compat-ui", "llm", "OpenAI Compatible", "connected", Map.of("apiKey", "k-compat")),
                adminScreenConnection("conn-off", "disconnected", Map.of("apiKey", "k-off")),
                adminScreenConnection("conn-empty-env", "connected", Map.of("apiKey_env", "CR165_SURELY_UNSET_ENV")));

        ResponseEntity<byte[]> res = controller.textToSpeech(new SpeechController.TtsRequest("안녕", null, null, null));

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(receivedBodies).isEmpty();
    }

    @Test
    void returns400WhenTextIsBlank() {
        ResponseEntity<byte[]> res = controller.textToSpeech(new SpeechController.TtsRequest("  ", null, null, null));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(receivedBodies).isEmpty();
    }

    @Test
    void passesUpstreamErrorStatus() {
        upstreamStatus = 400;
        givenConnections(adminScreenConnection("conn-1", "connected", Map.of("apiKey", "k-admin")));

        ResponseEntity<byte[]> res = controller.textToSpeech(new SpeechController.TtsRequest("안녕", null, null, null));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    private void givenConnections(ConnectionEntity... connections) {
        when(connectionRepository.findAll()).thenReturn(List.of(connections));
    }

    private static ConnectionEntity adminScreenConnection(String id, String status, Map<String, Object> config) {
        return connection(id, "llm", "OpenAI", status, config);
    }

    private static ConnectionEntity connection(String id, String type, String adapter, String status,
                                               Map<String, Object> config) {
        ConnectionEntity c = new ConnectionEntity();
        c.setId(id);
        c.setName(id);
        c.setType(type);
        c.setAdapter(adapter);
        c.setStatus(status);
        c.setConfig(new HashMap<>(config));
        return c;
    }
}
