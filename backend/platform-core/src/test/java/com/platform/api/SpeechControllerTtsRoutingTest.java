package com.platform.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ConnectionEntity;
import com.platform.repository.ConnectionRepository;
import com.platform.speech.LocalTtsService;
import com.platform.speech.OpenAiTtsService;
import com.platform.speech.SpeechException;
import com.platform.speech.SpeechService;
import com.platform.speech.TtsServiceRouter;
import com.platform.speech.TtsVoiceCatalog;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-165: 음성 합성 길 나누기와 목소리 목록(BIZ-136, BIZ-138, TC-TTS-211 ~ 223).
 *
 * <p>맥 음성 엔진과 OpenAI 대신 JVM 안의 HTTP 서버 둘이 받은 요청을 본다.
 */
class SpeechControllerTtsRoutingTest {

    private static final byte[] LOCAL_MP3 = {(byte) 0xFF, (byte) 0xFB, 0x10};
    private static final byte[] OPENAI_MP3 = {(byte) 0xFF, (byte) 0xF3, 0x20};
    private static final String VOICES_JSON = "[{\"id\":\"ko-female-01\",\"name\":\"밝은 비서\","
            + "\"description\":\"20대 여성\",\"openai_voice\":\"nova\",\"default\":true}]";
    private static final String BRIEFING = "회의는 2026-10-31 14:00 입니다";
    private static final String SPOKEN_BRIEFING = "회의는 2026년 10월 31일 오후 2시 입니다";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
    private final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    private final Map<String, String> settingValues = new HashMap<>();
    private final List<ConnectionEntity> connections = new ArrayList<>();

    private HttpServer fakeLocal;
    private HttpServer fakeOpenAi;
    private final List<String> localBodies = new ArrayList<>();
    private final List<String> localKeys = new ArrayList<>();
    private final List<String> openAiBodies = new ArrayList<>();
    private volatile int localStatus = 200;
    private volatile long localDelayMs = 0;

    private TtsVoiceCatalog catalog;
    private SpeechController controller;

    @BeforeEach
    void setUp() throws IOException {
        fakeLocal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeLocal.createContext("/synthesize", exchange -> {
            localBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            localKeys.add(exchange.getRequestHeaders().getFirst("X-Api-Key"));
            try {
                Thread.sleep(localDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = localStatus == 200 ? LOCAL_MP3 : "{\"detail\":\"fail\"}".getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(localStatus, out.length);
                exchange.getResponseBody().write(out);
            } catch (IOException ignored) {
                // 시간 초과 시험에서는 받는 쪽이 먼저 끊는다.
            }
            exchange.close();
        });
        fakeLocal.start();

        fakeOpenAi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeOpenAi.createContext("/v1/audio/speech", exchange -> {
            openAiBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, OPENAI_MP3.length);
            exchange.getResponseBody().write(OPENAI_MP3);
            exchange.close();
        });
        fakeOpenAi.start();

        when(connectionRepository.findAll()).thenAnswer(inv -> List.copyOf(connections));
        when(settings.getString(anyString(), any()))
                .thenAnswer(inv -> settingValues.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        when(settings.getDouble(anyString(), anyDouble()))
                .thenAnswer(inv -> settingValues.containsKey(inv.getArgument(0))
                        ? Double.parseDouble(settingValues.get(inv.<String>getArgument(0)))
                        : inv.getArgument(1));

        settingValues.put(TtsVoiceCatalog.KEY_VOICES, VOICES_JSON);
        settingValues.put(TtsVoiceCatalog.KEY_SPEED_MIN, "0.5");
        settingValues.put(TtsVoiceCatalog.KEY_SPEED_MAX, "2.0");

        String openAiUrl = "http://127.0.0.1:" + fakeOpenAi.getAddress().getPort() + "/v1/audio/speech";
        catalog = new TtsVoiceCatalog(settings);
        TtsServiceRouter router = new TtsServiceRouter(new LocalTtsService(connectionRepository),
                new OpenAiTtsService(connectionRepository, objectMapper, openAiUrl), catalog, settings);
        controller = new SpeechController(mock(SpeechService.class), router, catalog);
    }

    @AfterEach
    void tearDown() {
        fakeLocal.stop(0);
        fakeOpenAi.stop(0);
    }

    @Test
    @DisplayName("TC-TTS-211 맥 엔진 성공이면 local 이고 OpenAI 를 부르지 않는다")
    void usesLocalEngineForListedVoice() throws Exception {
        givenLocalConnection(Map.of("apiKey", "k-local"));
        givenOpenAiConnection();

        ResponseEntity<byte[]> res = tts(BRIEFING, null, "ko-female-01", 1.25);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getContentType()).hasToString("audio/mpeg");
        assertThat(res.getHeaders().getFirst(SpeechController.ENGINE_HEADER)).isEqualTo("local");
        assertThat(res.getBody()).isEqualTo(LOCAL_MP3);
        JsonNode sent = objectMapper.readTree(localBodies.get(0));
        assertThat(sent.get("text").asText()).isEqualTo(SPOKEN_BRIEFING);
        assertThat(sent.get("voice").asText()).isEqualTo("ko-female-01");
        assertThat(sent.get("speed").asDouble()).isEqualTo(1.25);
        assertThat(openAiBodies).isEmpty();
    }

    @Test
    @DisplayName("TC-TTS-212 맥 엔진 연결이 없으면 OpenAI 에 바꾼 글과 openai_voice, 요청 model")
    void fallsBackToOpenAiWhenNoLocalConnection() throws Exception {
        givenOpenAiConnection();

        ResponseEntity<byte[]> res = tts(BRIEFING, "tts-1-hd", "ko-female-01", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst(SpeechController.ENGINE_HEADER)).isEqualTo("openai");
        assertThat(res.getBody()).isEqualTo(OPENAI_MP3);
        JsonNode sent = objectMapper.readTree(openAiBodies.get(0));
        assertThat(sent.get("input").asText()).isEqualTo(SPOKEN_BRIEFING);
        assertThat(sent.get("voice").asText()).isEqualTo("nova");
        assertThat(sent.get("model").asText()).isEqualTo("tts-1-hd");
    }

    @ParameterizedTest(name = "맥 엔진 {0}")
    @DisplayName("TC-TTS-213 맥 엔진 500, 404 이면 OpenAI 로 전환")
    @ValueSource(ints = {500, 404})
    void fallsBackToOpenAiWhenLocalFails(int status) {
        givenLocalConnection(Map.of("apiKey", "k-local"));
        givenOpenAiConnection();
        localStatus = status;

        ResponseEntity<byte[]> res = tts(BRIEFING, null, "ko-female-01", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst(SpeechController.ENGINE_HEADER)).isEqualTo("openai");
        assertThat(localBodies).hasSize(1);
        assertThat(openAiBodies).hasSize(1);
    }

    @Test
    @DisplayName("TC-TTS-213 맥 엔진 시간 초과면 OpenAI 로 전환")
    void fallsBackToOpenAiWhenLocalTimesOut() {
        givenLocalConnection(Map.of("apiKey", "k-local", "timeout_seconds", 1));
        givenOpenAiConnection();
        localDelayMs = 2500;

        ResponseEntity<byte[]> res = tts(BRIEFING, null, "ko-female-01", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst(SpeechController.ENGINE_HEADER)).isEqualTo("openai");
    }

    @Test
    @DisplayName("TC-TTS-213 맥 엔진에 닿지 않으면(연결 거부) OpenAI 로 전환하고 원인을 남긴다")
    void fallsBackToOpenAiWhenLocalRefusesConnection() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        connections.add(connection("tts-local", LocalTtsService.CONNECTION_TYPE, null,
                Map.of("apiKey", "k-local", "base_url", "http://127.0.0.1:" + closedPort)));
        givenOpenAiConnection();

        ResponseEntity<byte[]> res = tts(BRIEFING, null, "ko-female-01", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst(SpeechController.ENGINE_HEADER)).isEqualTo("openai");
        assertThatThrownBy(() -> new LocalTtsService(connectionRepository).synthesize(SPOKEN_BRIEFING, "ko-female-01", 1.0))
                .isInstanceOf(SpeechException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotEndWith("null"));
    }

    @Test
    @DisplayName("TC-TTS-214 OpenAI 전환이 꺼져 있으면 503 이고 OpenAI 를 부르지 않는다")
    void returns503WhenFallbackDisabled() {
        givenLocalConnection(Map.of("apiKey", "k-local"));
        givenOpenAiConnection();
        localStatus = 500;
        settingValues.put(TtsServiceRouter.KEY_FALLBACK, "false");

        ResponseEntity<byte[]> res = tts(BRIEFING, null, "ko-female-01", null);

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(openAiBodies).isEmpty();
    }

    @Test
    @DisplayName("TC-TTS-215 tts.provider=openai 면 맥 엔진을 부르지 않고 OpenAI 에 바꾼 글")
    void skipsLocalWhenProviderIsOpenAi() throws Exception {
        givenLocalConnection(Map.of("apiKey", "k-local"));
        givenOpenAiConnection();
        settingValues.put(TtsServiceRouter.KEY_PROVIDER, "openai");

        ResponseEntity<byte[]> res = tts(BRIEFING, null, "ko-female-01", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(localBodies).isEmpty();
        assertThat(objectMapper.readTree(openAiBodies.get(0)).get("input").asText()).isEqualTo(SPOKEN_BRIEFING);
    }

    @ParameterizedTest(name = "목소리 [{0}]")
    @DisplayName("TC-TTS-216 목록 밖 목소리와 빈 목소리는 지금처럼 OpenAI, 글 그대로")
    @CsvSource(value = {"nova", "NULL"}, nullValues = "NULL")
    void keepsUnlistedVoiceOnOpenAiWithOriginalText(String voice) throws Exception {
        givenLocalConnection(Map.of("apiKey", "k-local"));
        givenOpenAiConnection();

        ResponseEntity<byte[]> res = tts(BRIEFING, null, voice, 3.0);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getFirst(SpeechController.ENGINE_HEADER)).isEqualTo("openai");
        assertThat(localBodies).isEmpty();
        JsonNode sent = objectMapper.readTree(openAiBodies.get(0));
        assertThat(sent.get("input").asText()).isEqualTo(BRIEFING);
        assertThat(sent.get("voice").asText()).isEqualTo(voice != null ? voice : "alloy");
        assertThat(sent.get("speed").asDouble()).isEqualTo(3.0);
    }

    @ParameterizedTest(name = "speed {0} = {1}")
    @DisplayName("TC-TTS-217 목록 길 speed 범위")
    @CsvSource({"0.49, 400", "0.5, 200", "2.0, 200", "2.01, 400"})
    void checksSpeedRangeOnListedVoice(double speed, int expected) {
        givenLocalConnection(Map.of("apiKey", "k-local"));

        ResponseEntity<byte[]> res = tts("안녕", null, "ko-female-01", speed);

        assertThat(res.getStatusCode().value()).isEqualTo(expected);
        assertThat(localBodies).hasSize(expected == 200 ? 1 : 0);
    }

    @Test
    @DisplayName("TC-TTS-218 OpenAI 로 전환했는데 OpenAI 연결이 없으면 503")
    void returns503WhenFallbackHasNoOpenAiConnection() {
        givenLocalConnection(Map.of("apiKey", "k-local"));
        localStatus = 500;

        ResponseEntity<byte[]> res = tts(BRIEFING, null, "ko-female-01", null);

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(openAiBodies).isEmpty();
    }

    @Test
    @DisplayName("TC-TTS-219 맥 엔진 키는 apiKey_env 가 가리키는 환경변수 값으로 간다")
    void sendsLocalKeyFromEnvironmentVariable() {
        String envName = "HOME";
        String envValue = System.getenv(envName);
        assertThat(envValue).isNotBlank();
        givenLocalConnection(Map.of("apiKey_env", envName));

        ResponseEntity<byte[]> res = tts("안녕", null, "ko-female-01", null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(localKeys).containsExactly(envValue);
        assertThat(connections.get(0).getConfig()).doesNotContainValue(envValue);
    }

    @Test
    @DisplayName("TC-TTS-221, 223 목록은 맥 엔진을 부르지 않고 openai_voice 를 내보내지 않는다")
    void listsVoicesWithoutOpenAiVoice() throws Exception {
        givenLocalConnection(Map.of("apiKey", "k-local"));
        fakeLocal.stop(0);

        ResponseEntity<ApiResponse<Map<String, Object>>> res = controller.listVoices();

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode data = objectMapper.valueToTree(Objects.requireNonNull(res.getBody()).data());
        assertThat(data.toString()).doesNotContain("openai_voice").doesNotContain("nova");
        JsonNode voice = data.get("voices").get(0);
        assertThat(voice.get("id").asText()).isEqualTo("ko-female-01");
        assertThat(voice.get("name").asText()).isEqualTo("밝은 비서");
        assertThat(voice.get("description").asText()).isEqualTo("20대 여성");
        assertThat(voice.get("default").asBoolean()).isTrue();
        assertThat(data.get("speed").get("min").asDouble()).isEqualTo(0.5);
        assertThat(data.get("speed").get("max").asDouble()).isEqualTo(2.0);
        assertThat(data.get("speed").get("default").asDouble()).isEqualTo(1.0);
        assertThat(localBodies).isEmpty();
    }

    @ParameterizedTest(name = "설정 [{0}]")
    @DisplayName("TC-TTS-222 목록 설정이 깨졌거나 없으면 빈 목록이고 모든 요청이 OpenAI 길")
    @CsvSource(value = {"not-json", "NULL"}, nullValues = "NULL")
    void treatsBrokenOrMissingCatalogAsEmpty(String raw) throws Exception {
        if (raw == null) settingValues.remove(TtsVoiceCatalog.KEY_VOICES);
        else settingValues.put(TtsVoiceCatalog.KEY_VOICES, raw);
        givenLocalConnection(Map.of("apiKey", "k-local"));
        givenOpenAiConnection();

        JsonNode data = objectMapper.valueToTree(Objects.requireNonNull(controller.listVoices().getBody()).data());
        ResponseEntity<byte[]> res = tts(BRIEFING, null, "ko-female-01", null);

        assertThat(data.get("voices")).isEmpty();
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(localBodies).isEmpty();
        assertThat(objectMapper.readTree(openAiBodies.get(0)).get("input").asText()).isEqualTo(BRIEFING);
    }

    private ResponseEntity<byte[]> tts(String text, String model, String voice, Double speed) {
        return controller.textToSpeech(new SpeechController.TtsRequest(text, model, voice, speed));
    }

    private void givenLocalConnection(Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>(extra);
        config.put("base_url", "http://127.0.0.1:" + fakeLocal.getAddress().getPort());
        connections.add(connection("tts-local", LocalTtsService.CONNECTION_TYPE, null, config));
    }

    private void givenOpenAiConnection() {
        connections.add(connection("openai-1", "llm", "OpenAI", Map.of("apiKey", "k-openai")));
    }

    private static ConnectionEntity connection(String id, String type, String adapter, Map<String, Object> config) {
        ConnectionEntity c = new ConnectionEntity();
        c.setId(id);
        c.setName(id);
        c.setType(type);
        c.setAdapter(adapter);
        c.setStatus("connected");
        c.setConfig(new HashMap<>(config));
        return c;
    }
}
