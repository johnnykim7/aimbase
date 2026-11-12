package com.platform.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.api.WidgetTtsController;
import com.platform.auth.UserPrincipal;
import com.platform.auth.WidgetScopes;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ConnectionEntity;
import com.platform.policy.AuditLogger;
import com.platform.repository.ConnectionRepository;
import com.platform.session.ChatRejectedException;
import com.platform.tenant.TenantContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-186: 위젯 답 읽어 주기(TC-CHT-120, 121, 127). 맥 음성 엔진과 OpenAI 대신 JVM 안의 HTTP 서버 둘이 받은 요청을 본다.
 */
class WidgetTtsServiceTest {

    private static final byte[] LOCAL_MP3 = {(byte) 0xFF, (byte) 0xFB, 0x10};
    private static final byte[] OPENAI_MP3 = {(byte) 0xFF, (byte) 0xF3, 0x20};
    private static final String VOICES_JSON = "[{\"id\":\"ko-female-01\",\"name\":\"밝은 비서\","
            + "\"description\":\"20대 여성\",\"openai_voice\":\"nova\",\"default\":true}]";
    private static final String TEXT = "회의는 오후 두 시에 시작합니다.";

    private final ConnectionRepository connectionRepository = mock(ConnectionRepository.class);
    private final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    private final WidgetRateLimiter rateLimiter = mock(WidgetRateLimiter.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private final Map<String, String> settingValues = new HashMap<>();
    private final List<ConnectionEntity> connections = new ArrayList<>();
    private final List<String> localBodies = Collections.synchronizedList(new ArrayList<>());
    private final List<String> openAiBodies = Collections.synchronizedList(new ArrayList<>());
    private volatile long localDelayMs = 0;
    private volatile int localStatus = 200;

    private HttpServer fakeLocal;
    private HttpServer fakeOpenAi;
    private TtsServiceRouter router;
    private WidgetTtsService service;
    private WidgetTtsController controller;

    @BeforeEach
    void setUp() throws IOException {
        TenantContext.setTenantId("t1");
        fakeLocal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeLocal.createContext("/synthesize", exchange -> {
            localBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
                        ? Double.parseDouble(settingValues.get(inv.<String>getArgument(0))) : inv.getArgument(1));
        when(settings.getInt(anyString(), anyInt()))
                .thenAnswer(inv -> settingValues.containsKey(inv.getArgument(0))
                        ? Integer.parseInt(settingValues.get(inv.<String>getArgument(0))) : inv.getArgument(1));
        when(settings.getLong(anyString(), anyLong()))
                .thenAnswer(inv -> settingValues.containsKey(inv.getArgument(0))
                        ? Long.parseLong(settingValues.get(inv.<String>getArgument(0))) : inv.getArgument(1));
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        settingValues.put(TtsVoiceCatalog.KEY_VOICES, VOICES_JSON);

        TtsVoiceCatalog catalog = new TtsVoiceCatalog(settings);
        String openAiUrl = "http://127.0.0.1:" + fakeOpenAi.getAddress().getPort() + "/v1/audio/speech";
        router = new TtsServiceRouter(new LocalTtsService(connectionRepository),
                new OpenAiTtsService(connectionRepository, new ObjectMapper(), openAiUrl), catalog, settings);
        service = new WidgetTtsService(router, catalog, rateLimiter, settings, auditLogger);
        controller = new WidgetTtsController(service);
    }

    @AfterEach
    void tearDown() {
        fakeLocal.stop(0);
        fakeOpenAi.stop(0);
        TenantContext.clear();
    }

    @Test
    @DisplayName("TC-CHT-120 빈 글, 상한 넘는 글, 꺼짐, 분당 상한은 엔진을 부르지 않고 거절한다")
    void rejectsBeforeCallingEngine() {
        givenLocalConnection();
        givenOpenAiConnection();

        assertRejected(() -> service.synthesize("s1", "  ", "u1"), 400, "tts_invalid:text");
        assertRejected(() -> service.synthesize("s1", "가".repeat(301), "u1"), 400, "tts_invalid:text");

        settingValues.put(WidgetTtsService.KEY_ENABLED, "false");
        assertRejected(() -> service.synthesize("s1", TEXT, "u1"), 409, "tts_disabled");
        settingValues.remove(WidgetTtsService.KEY_ENABLED);

        when(rateLimiter.tryAcquire(eq("tts:rate:t1:s1"), eq(60))).thenReturn(false);
        assertRejected(() -> service.synthesize("s1", TEXT, "u1"), 429, "tts_rate_limited");

        assertThat(localBodies).isEmpty();
        assertThat(openAiBodies).isEmpty();
        verify(auditLogger, never()).log(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("TC-CHT-120 위젯 토큰에 chat:tts 가 없으면 403 tts_forbidden")
    void rejectsWidgetTokenWithoutScope() {
        Authentication noScope = widgetAuth("chat:stream");
        assertRejected(() -> controller.tts(new WidgetTtsController.TtsRequest("s1", TEXT), noScope), 403, "tts_forbidden");
        assertThat(localBodies).isEmpty();

        givenLocalConnection();
        ResponseEntity<byte[]> ok = controller.tts(new WidgetTtsController.TtsRequest("s1", TEXT), widgetAuth("chat:tts"));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("TC-CHT-121 위젯 경로는 맥 엔진을 위젯 시한만 기다리고 OpenAI 로 넘긴다. 위젯 밖은 연결 시한 그대로")
    void widgetPathUsesShortLocalTimeout() {
        givenLocalConnection();
        givenOpenAiConnection();
        settingValues.put(WidgetTtsService.KEY_LOCAL_TIMEOUT, "1");
        localDelayMs = 1800;

        TtsServiceRouter.Result widget = service.synthesize("s1", TEXT, "u1");
        assertThat(widget.engine()).isEqualTo("openai");
        assertThat(widget.body()).isEqualTo(OPENAI_MP3);

        TtsServiceRouter.Result outside = router.synthesize(TEXT, null, "ko-female-01", 1.0);
        assertThat(outside.engine()).isEqualTo(TtsServiceRouter.ENGINE_LOCAL);
        assertThat(outside.body()).isEqualTo(LOCAL_MP3);
    }

    @Test
    @DisplayName("TC-CHT-121 맥 엔진이 실패하고 OpenAI 전환이 꺼져 있으면 503 tts_unavailable")
    void unavailableWhenFallbackDisabled() {
        givenLocalConnection();
        givenOpenAiConnection();
        localStatus = 500;
        settingValues.put(TtsServiceRouter.KEY_FALLBACK, "false");

        assertRejected(() -> service.synthesize("s1", TEXT, "u1"), 503, "tts_unavailable");
        assertThat(openAiBodies).isEmpty();
    }

    @Test
    @DisplayName("TC-CHT-121 목소리 설정이 비면 목록의 기본 목소리로 맥 엔진에 보낸다")
    void usesCatalogDefaultVoice() {
        givenLocalConnection();

        TtsServiceRouter.Result r = service.synthesize("s1", TEXT, "u1");

        assertThat(r.body()).isEqualTo(LOCAL_MP3);
        assertThat(localBodies).hasSize(1);
        assertThat(localBodies.get(0)).contains("ko-female-01");
    }

    @Test
    @DisplayName("TC-CHT-127 읽기 감사 로그에는 글자 수와 엔진만 있고 글 본문은 없다")
    @SuppressWarnings("unchecked")
    void auditsWithoutText() {
        givenLocalConnection();

        service.synthesize("s1", TEXT, "u1");

        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditLogger).log(eq(WidgetTtsService.AUDIT_ACTION), eq("widget_tts"), eq("u1"), eq("s1"), detail.capture(), any());
        assertThat(detail.getValue()).containsEntry("chars", TEXT.length()).containsKey("engine").containsKey("elapsed_ms");
        assertThat(detail.getValue().values()).noneMatch(v -> String.valueOf(v).contains("회의"));
    }

    private void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(ChatRejectedException.class)
                .satisfies(e -> {
                    ChatRejectedException ex = (ChatRejectedException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(status);
                    assertThat(ex.getMessage()).isEqualTo(code);
                });
    }

    static Authentication widgetAuth(String... scopes) {
        UserPrincipal p = new UserPrincipal("u1", "u1", "t1", WidgetScopes.WIDGET_ROLE, Map.of());
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        for (String s : scopes) authorities.add(new SimpleGrantedAuthority("SCOPE_" + s));
        return new UsernamePasswordAuthenticationToken(p, null, authorities);
    }

    private void givenLocalConnection() {
        Map<String, Object> config = new HashMap<>();
        config.put("apiKey", "k-local");
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
