package com.platform.speech.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.api.VoiceTicketController;
import com.platform.auth.UserPrincipal;
import com.platform.auth.WidgetScopes;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ConnectionEntity;
import com.platform.policy.AuditLogger;
import com.platform.session.ChatRejectedException;
import com.platform.speech.SttLocalConnections;
import com.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CR-186: 음성 대화 입장권과 실시간 받아쓰기 중계(TC-CHT-123 ~ 127). 받아쓰기 서버는 가짜 클라이언트로 바꾼다.
 */
class VoiceRelayTest {

    private static final String ORIGIN = "https://wes.example.com";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    private final SttLocalConnections sttConnections = mock(SttLocalConnections.class);
    private final AuditLogger auditLogger = mock(AuditLogger.class);
    private final Map<String, String> settingValues = new HashMap<>();
    private final Map<String, String> redisStore = new ConcurrentHashMap<>();
    private final Map<String, Duration> redisTtl = new ConcurrentHashMap<>();
    private final FakeStt stt = new FakeStt();
    private final VoiceRelayRegistry registry = new VoiceRelayRegistry();

    private VoiceTicketService ticketService;
    private VoiceTicketHandshakeInterceptor interceptor;
    private VoiceRelayHandler handler;
    private ConnectionEntity sttConnection;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.setTenantId("t1");
        when(settings.getString(anyString(), any()))
                .thenAnswer(inv -> settingValues.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        when(settings.getInt(anyString(), anyInt()))
                .thenAnswer(inv -> settingValues.containsKey(inv.getArgument(0))
                        ? Integer.parseInt(settingValues.get(inv.<String>getArgument(0))) : inv.getArgument(1));
        when(settings.getLong(anyString(), anyLong()))
                .thenAnswer(inv -> settingValues.containsKey(inv.getArgument(0))
                        ? Long.parseLong(settingValues.get(inv.<String>getArgument(0))) : inv.getArgument(1));
        when(settings.getDouble(anyString(), anyDouble()))
                .thenAnswer(inv -> inv.getArgument(1));

        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            redisTtl.put(inv.getArgument(0), inv.getArgument(2));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.getAndDelete(anyString())).thenAnswer(inv -> redisStore.remove(inv.<String>getArgument(0)));

        sttConnection = new ConnectionEntity();
        sttConnection.setId("stt-1");
        sttConnection.setType("stt_local");
        sttConnection.setStatus("connected");
        sttConnection.setConfig(new HashMap<>(Map.of("base_url", "http://127.0.0.1:9100/", "api_key", "k-stt")));
        when(sttConnections.findActive()).thenReturn(Optional.of(sttConnection));
        when(sttConnections.findById("stt-1")).thenReturn(Optional.of(sttConnection));

        ticketService = new VoiceTicketService(redis, objectMapper, settings, sttConnections, registry);
        interceptor = new VoiceTicketHandshakeInterceptor(ticketService);
        handler = new VoiceRelayHandler(ticketService, registry, sttConnections, stt, settings, auditLogger, objectMapper);
    }

    @AfterEach
    void tearDown() {
        handler.shutdown();
        TenantContext.clear();
    }

    // ---- TC-CHT-123 입장권 발급 ----

    @Test
    @DisplayName("TC-CHT-123 입장권은 30초짜리이고 Redis 에 테넌트, 세션, 사람, 출처, 받아쓰기 연결을 둔다")
    void issuesTicket() throws Exception {
        Map<String, Object> body = ticketService.issue("s1", "u1", ORIGIN);

        String ticket = (String) body.get("ticket");
        assertThat(ticket).startsWith("vt_").hasSizeGreaterThan(20);
        assertThat(body).containsEntry("expires_in_seconds", 30L).containsEntry("stream_path", VoiceTicketService.STREAM_PATH);
        assertThat(redisTtl.get("voice:ticket:" + ticket)).isEqualTo(Duration.ofSeconds(30));
        JsonNode stored = objectMapper.readTree(redisStore.get("voice:ticket:" + ticket));
        assertThat(stored.path("tenant_id").asText()).isEqualTo("t1");
        assertThat(stored.path("session_id").asText()).isEqualTo("s1");
        assertThat(stored.path("user_ref").asText()).isEqualTo("u1");
        assertThat(stored.path("origin").asText()).isEqualTo(ORIGIN);
        assertThat(stored.path("connection_id").asText()).isEqualTo("stt-1");
    }

    @Test
    @DisplayName("TC-CHT-123 꺼짐 409, 받아쓰기 연결 없음 503, 자리 없음 429, 같은 세션 중계 열림 409, 세션 없음 400")
    void rejectsTicket() {
        assertRejected(() -> ticketService.issue(" ", "u1", ORIGIN), 400, "voice_invalid:session_id");

        settingValues.put(VoiceTicketService.KEY_ENABLED, "false");
        assertRejected(() -> ticketService.issue("s1", "u1", ORIGIN), 409, "voice_disabled");
        settingValues.remove(VoiceTicketService.KEY_ENABLED);

        when(sttConnections.findActive()).thenReturn(Optional.empty());
        assertRejected(() -> ticketService.issue("s1", "u1", ORIGIN), 503, "voice_unavailable");
        when(sttConnections.findActive()).thenReturn(Optional.of(sttConnection));

        registry.tryOpen(VoiceRelayRegistry.key("t1", "s1"), 3);
        assertRejected(() -> ticketService.issue("s1", "u1", ORIGIN), 409, "voice_session_busy");
        registry.tryOpen("t1:a", 3);
        registry.tryOpen("t1:b", 3);
        assertRejected(() -> ticketService.issue("s2", "u1", ORIGIN), 429, "voice_busy");

        assertThat(redisStore).isEmpty();
    }

    @Test
    @DisplayName("TC-CHT-123 위젯 토큰에 chat:voice 가 없으면 403 voice_forbidden")
    void rejectsWidgetTokenWithoutScope() {
        VoiceTicketController controller = new VoiceTicketController(ticketService);
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("Origin", ORIGIN);

        assertRejected(() -> controller.issue(new VoiceTicketController.TicketRequest("s1"), widgetAuth("chat:tts"), http),
                403, "voice_forbidden");
        assertThat(redisStore).isEmpty();

        assertThat(controller.issue(new VoiceTicketController.TicketRequest("s1"), widgetAuth("chat:voice"), http).data())
                .containsKey("ticket");
    }

    // ---- TC-CHT-124 입장권 한 번 쓰기 ----

    @Test
    @DisplayName("TC-CHT-124 같은 입장권 두 번째, 지난 입장권, 없는 입장권은 4401, 다른 출처는 4403. 받아쓰기 서버에 잇지 않는다")
    void ticketIsSingleUse() throws Exception {
        String ticket = issue("s1");
        Ws first = connect(ticket, ORIGIN);
        assertThat(first.closed.get()).isNull();
        assertThat(stt.opens).isEqualTo(1);

        assertThat(connect(ticket, ORIGIN).closed.get().getCode()).isEqualTo(4401);

        String expired = issue("s2");
        redisStore.remove("voice:ticket:" + expired);
        assertThat(connect(expired, ORIGIN).closed.get().getCode()).isEqualTo(4401);

        assertThat(connect("vt_nope", ORIGIN).closed.get().getCode()).isEqualTo(4401);
        assertThat(connect(null, ORIGIN).closed.get().getCode()).isEqualTo(4401);

        String other = issue("s3");
        assertThat(connect(other, "https://evil.example.com").closed.get().getCode()).isEqualTo(4403);

        assertThat(stt.opens).isEqualTo(1);
    }

    // ---- TC-CHT-125 중계 ----

    @Test
    @DisplayName("TC-CHT-125 소리와 끝을 그대로 넘기고, 받아쓴 글을 옮기고, 마지막 글 뒤 1000 으로 닫는다")
    void relaysAudioAndTranscripts() throws Exception {
        Ws ws = connect(issue("s1"), ORIGIN);
        assertThat(stt.baseUrl).isEqualTo("http://127.0.0.1:9100/");
        assertThat(stt.apiKey).isEqualTo("k-stt");
        assertThat(stt.sessionId).isEqualTo("s1");
        assertThat(ws.sent).containsExactly("{\"type\":\"ready\"}");

        byte[][] chunks = {{1, 2, 3}, {4, 5}, {6}};
        for (byte[] c : chunks) handler.handleMessage(ws.session, new BinaryMessage(c));
        handler.handleMessage(ws.session, new TextMessage("{\"type\":\"end\"}"));

        assertThat(stt.stream.audio).containsExactly(chunks);
        assertThat(stt.stream.ends).isEqualTo(1);

        String t1 = "{\"type\":\"transcript\",\"text\":\"안녕\",\"delta\":\"안녕\",\"buffer\":\"하세\"}";
        String t2 = "{\"type\":\"transcript\",\"text\":\"안녕하세요\",\"delta\":\"하세요\",\"buffer\":\"\"}";
        String fin = "{\"type\":\"final\",\"text\":\"안녕하세요\"}";
        stt.listener.onText(t1);
        stt.listener.onText(t2);
        stt.listener.onText(fin);

        assertThat(ws.sent).containsExactly("{\"type\":\"ready\"}", t1, t2, fin);
        assertThat(ws.closed.get().getCode()).isEqualTo(1000);
        assertThat(stt.stream.closed).isTrue();
        assertThat(registry.openCount()).isZero();
    }

    @Test
    @DisplayName("TC-CHT-125 받아쓰기 서버 주소는 ws, wss 로 바꾸고 키와 세션을 붙인다")
    void buildsStreamUrl() {
        assertThat(SttStreamClient.streamUrl("http://10.0.0.5:9100/", "k y", "s1"))
                .isEqualTo("ws://10.0.0.5:9100/stream?api_key=k+y&session_id=s1");
        assertThat(SttStreamClient.streamUrl("https://stt.example.com", "k", "s1"))
                .isEqualTo("wss://stt.example.com/stream?api_key=k&session_id=s1");
    }

    // ---- TC-CHT-126 끊김과 시한 ----

    @Test
    @DisplayName("TC-CHT-126 받아쓰기 서버에 잇지 못하면 1011 이고 자리를 푼다")
    void closesWhenUpstreamUnreachable() throws Exception {
        stt.fail = true;
        Ws ws = connect(issue("s1"), ORIGIN);

        assertThat(ws.closed.get().getCode()).isEqualTo(1011);
        assertThat(ws.sent).isEmpty();
        assertThat(registry.openCount()).isZero();
    }

    @Test
    @DisplayName("TC-CHT-126 end 뒤 마지막 글이 시한 안에 없으면 1011")
    void closesWhenFinalTimesOut() throws Exception {
        settingValues.put(VoiceRelayHandler.KEY_FINAL_TIMEOUT, "1");
        Ws ws = connect(issue("s1"), ORIGIN);
        handler.handleMessage(ws.session, new TextMessage("{\"type\":\"end\"}"));

        waitFor(() -> ws.closed.get() != null, 4000);
        assertThat(ws.closed.get().getCode()).isEqualTo(1011);
        assertThat(registry.openCount()).isZero();
    }

    @Test
    @DisplayName("TC-CHT-126 발화가 상한에 5초를 더한 시간을 넘으면 4408")
    void closesWhenUtteranceTooLong() throws Exception {
        settingValues.put(VoiceRelayHandler.KEY_MAX_UTTERANCE, "1");
        Ws ws = connect(issue("s1"), ORIGIN);

        Thread.sleep(4500);
        assertThat(ws.closed.get()).isNull();
        waitFor(() -> ws.closed.get() != null, 4000);
        assertThat(ws.closed.get().getCode()).isEqualTo(4408);
        assertThat(registry.openCount()).isZero();
    }

    // ---- TC-CHT-127 감사 로그 ----

    @Test
    @DisplayName("TC-CHT-127 중계 감사 로그에는 이은 시간, 바이트, 끝난 까닭만 있고 받아쓴 글은 없다")
    @SuppressWarnings("unchecked")
    void auditsRelayWithoutTranscript() throws Exception {
        Ws ws = connect(issue("s1"), ORIGIN);
        handler.handleMessage(ws.session, new BinaryMessage(new byte[]{1, 2, 3, 4}));
        handler.handleMessage(ws.session, new TextMessage("{\"type\":\"end\"}"));
        stt.listener.onText("{\"type\":\"final\",\"text\":\"비밀 내용\"}");
        handler.afterConnectionClosed(ws.session, ws.closed.get());

        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditLogger).log(eq(VoiceRelayHandler.AUDIT_ACTION), eq("voice_stream"), eq("u1"), eq("s1"), detail.capture(), any());
        assertThat(detail.getValue()).containsEntry("audio_bytes", 4L).containsEntry("end_reason", "final").containsKey("duration_ms");
        assertThat(detail.getValue().values()).noneMatch(v -> String.valueOf(v).contains("비밀"));
    }

    // ---- 도구 ----

    private String issue(String sessionId) {
        return (String) ticketService.issue(sessionId, "u1", ORIGIN).get("ticket");
    }

    /** 손잡기와 연결 열림까지. 컨테이너가 하는 것처럼 같은 속성 표를 넘긴다. */
    private Ws connect(String ticket, String origin) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", VoiceTicketService.STREAM_PATH);
        if (ticket != null) req.setQueryString("ticket=" + ticket);
        req.addHeader("Origin", origin);
        Ws ws = new Ws();
        boolean accepted = interceptor.beforeHandshake(new ServletServerHttpRequest(req),
                new ServletServerHttpResponse(new MockHttpServletResponse()), handler, ws.attributes);
        assertThat(accepted).isTrue();
        handler.afterConnectionEstablished(ws.session);
        return ws;
    }

    private static void waitFor(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < until) Thread.sleep(50);
    }

    private void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(ChatRejectedException.class)
                .satisfies(e -> {
                    assertThat(((ChatRejectedException) e).getStatus().value()).isEqualTo(status);
                    assertThat(e.getMessage()).isEqualTo(code);
                });
    }

    private static Authentication widgetAuth(String... scopes) {
        UserPrincipal p = new UserPrincipal("u1", "u1", "t1", WidgetScopes.WIDGET_ROLE, Map.of());
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        for (String s : scopes) authorities.add(new SimpleGrantedAuthority("SCOPE_" + s));
        return new UsernamePasswordAuthenticationToken(p, null, authorities);
    }

    /** 위젯 쪽 웹소켓 하나. 보낸 글과 닫은 번호를 모은다. */
    static final class Ws {
        final Map<String, Object> attributes = new ConcurrentHashMap<>();
        final List<String> sent = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<CloseStatus> closed = new AtomicReference<>();
        final WebSocketSession session = mock(WebSocketSession.class);

        Ws() throws IOException {
            when(session.getAttributes()).thenReturn(attributes);
            when(session.getId()).thenReturn("ws-" + System.nanoTime());
            when(session.isOpen()).thenAnswer(inv -> closed.get() == null);
            doAnswer(inv -> {
                WebSocketMessage<?> m = inv.getArgument(0);
                sent.add(String.valueOf(m.getPayload()));
                return null;
            }).when(session).sendMessage(any());
            doAnswer(inv -> {
                closed.compareAndSet(null, inv.getArgument(0));
                return null;
            }).when(session).close(any(CloseStatus.class));
        }
    }

    static final class FakeStt implements SttStreamClient {
        volatile boolean fail;
        int opens;
        String baseUrl;
        String apiKey;
        String sessionId;
        Listener listener;
        final FakeStream stream = new FakeStream();

        @Override
        public CompletableFuture<Stream> open(String baseUrl, String apiKey, String sessionId, Listener listener) {
            opens++;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.sessionId = sessionId;
            this.listener = listener;
            return fail ? CompletableFuture.failedFuture(new IOException("connection refused"))
                    : CompletableFuture.completedFuture(stream);
        }
    }

    static final class FakeStream implements SttStreamClient.Stream {
        final List<byte[]> audio = Collections.synchronizedList(new ArrayList<>());
        volatile int ends;
        volatile boolean closed;

        @Override
        public void sendAudio(byte[] chunk) {
            audio.add(chunk.clone());
        }

        @Override
        public void sendEnd() {
            ends++;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
