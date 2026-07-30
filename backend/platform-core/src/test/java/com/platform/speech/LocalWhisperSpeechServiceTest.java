package com.platform.speech;

import com.platform.domain.ConnectionEntity;
import com.platform.repository.ConnectionRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CR-133: 사이드카 클라이언트 단위 테스트.
 * 실제 HTTP 서버를 띄워 요청 헤더·multipart 조립·응답 파싱을 검증한다.
 */
class LocalWhisperSpeechServiceTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";

    private ConnectionRepository connectionRepository;
    private LocalWhisperSpeechService service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/transcribe", exchange -> {
            lastApiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
            try (InputStream in = exchange.getRequestBody()) {
                lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();

        connectionRepository = mock(ConnectionRepository.class);
        service = new LocalWhisperSpeechService(connectionRepository);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private ConnectionEntity sidecarConnection(String apiKey) {
        ConnectionEntity c = new ConnectionEntity();
        c.setId("stt-local-1");
        c.setName("mac whisper");
        c.setType(LocalWhisperSpeechService.CONNECTION_TYPE);
        c.setAdapter("http");
        c.setStatus("connected");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("base_url", "http://127.0.0.1:" + port);
        if (apiKey != null) cfg.put("api_key", apiKey);
        c.setConfig(cfg);
        return c;
    }

    @Test
    @DisplayName("정상 응답을 파싱하고 X-Api-Key 를 보낸다")
    void transcribe_success() {
        when(connectionRepository.findAll()).thenReturn(List.of(sidecarConnection("secret-key")));
        responseBody = """
                {"text":"안녕하세요 회의를 시작합니다","language":"ko","duration":632.9,
                 "elapsed":43.7,"speed_ratio":14.49,
                 "segments":[{"start":0.0,"end":5.0,"text":"안녕하세요"},
                             {"start":5.0,"end":10.0,"text":"회의를 시작합니다"}]}
                """;

        var r = service.transcribe("audio-bytes".getBytes(StandardCharsets.UTF_8),
                "audio/wav", "meeting.wav", "ko", null);

        assertEquals("안녕하세요 회의를 시작합니다", r.text());
        assertEquals("ko", r.language());
        assertEquals(632.9, r.durationSec(), 0.01);
        assertEquals(43.7, r.elapsedSec(), 0.01);
        assertEquals(2, r.segments().size());
        assertEquals("안녕하세요", r.segments().get(0).get("text"));

        // 사이드카는 fail-closed 이므로 키가 반드시 실려야 한다.
        assertEquals("secret-key", lastApiKey.get());
        assertTrue(lastBody.get().contains("filename=\"meeting.wav\""));
        assertTrue(lastBody.get().contains("name=\"language\""));
    }

    @Test
    @DisplayName("language=auto 이면 language 파트를 보내지 않는다(사이드카 자동 감지)")
    void transcribe_autoLanguage_omitsField() {
        when(connectionRepository.findAll()).thenReturn(List.of(sidecarConnection("k")));
        responseBody = "{\"text\":\"hi\",\"language\":\"en\",\"segments\":[]}";

        service.transcribe("x".getBytes(StandardCharsets.UTF_8), "audio/wav", "a.wav", "auto", null);

        assertFalse(lastBody.get().contains("name=\"language\""));
    }

    @Test
    @DisplayName("api_key 가 없으면 호출 전에 PROVIDER_UNAVAILABLE 로 막는다")
    void transcribe_missingApiKey_failsFast() {
        when(connectionRepository.findAll()).thenReturn(List.of(sidecarConnection(null)));

        SpeechException e = assertThrows(SpeechException.class, () ->
                service.transcribe("x".getBytes(StandardCharsets.UTF_8), "audio/wav", "a.wav", "ko", null));

        assertEquals(SpeechException.ErrorCode.PROVIDER_UNAVAILABLE, e.getCode());
        assertNull(lastApiKey.get(), "호출 자체가 나가면 안 된다");
    }

    @Test
    @DisplayName("활성 stt_local 커넥션이 없으면 PROVIDER_UNAVAILABLE")
    void transcribe_noConnection() {
        when(connectionRepository.findAll()).thenReturn(List.of());

        SpeechException e = assertThrows(SpeechException.class, () ->
                service.transcribe("x".getBytes(StandardCharsets.UTF_8), "audio/wav", "a.wav", "ko", null));

        assertEquals(SpeechException.ErrorCode.PROVIDER_UNAVAILABLE, e.getCode());
    }

    @Test
    @DisplayName("사이드카 401(키 불일치)은 UPSTREAM_ERROR 로 승격한다")
    void transcribe_unauthorized_mapsToUpstream() {
        when(connectionRepository.findAll()).thenReturn(List.of(sidecarConnection("wrong")));
        responseStatus = 401;
        responseBody = "{\"detail\":\"invalid or missing X-Api-Key\"}";

        SpeechException e = assertThrows(SpeechException.class, () ->
                service.transcribe("x".getBytes(StandardCharsets.UTF_8), "audio/wav", "a.wav", "ko", null));

        assertEquals(SpeechException.ErrorCode.UPSTREAM_ERROR, e.getCode());
    }

    @Test
    @DisplayName("본문이 실제로 서버에 도달한다(HTTP/1.1 고정 회귀 방어)")
    void transcribe_bodyActuallyArrives() {
        // 기본 HttpClient(HTTP_2)는 h2c 업그레이드를 시도하고 uvicorn(h11)이 이를 거부하면서
        // Content-Length 만 남고 본문이 0바이트로 유실됐다 → 사이드카가 422(file 누락).
        when(connectionRepository.findAll()).thenReturn(List.of(sidecarConnection("k")));
        responseBody = "{\"text\":\"ok\",\"language\":\"ko\",\"segments\":[]}";
        byte[] audio = "PAYLOAD-MUST-ARRIVE".getBytes(StandardCharsets.UTF_8);

        service.transcribe(audio, "audio/wav", "a.wav", "ko", null);

        assertNotNull(lastBody.get(), "본문이 서버에 도달하지 않았다");
        assertTrue(lastBody.get().contains("PAYLOAD-MUST-ARRIVE"),
                "오디오 바이트가 유실됐다 — HTTP 버전 협상 회귀 의심");
    }

    @Test
    @DisplayName("base_url 끝 슬래시가 있어도 //transcribe 로 깨지지 않는다")
    void transcribe_trailingSlashBaseUrl() {
        ConnectionEntity c = sidecarConnection("k");
        c.getConfig().put("base_url", "http://127.0.0.1:" + port + "/");
        when(connectionRepository.findAll()).thenReturn(List.of(c));
        responseBody = "{\"text\":\"ok\",\"language\":\"ko\",\"segments\":[]}";

        var r = service.transcribe("x".getBytes(StandardCharsets.UTF_8), "audio/wav", "a.wav", "ko", null);

        assertEquals("ok", r.text());
    }
}
