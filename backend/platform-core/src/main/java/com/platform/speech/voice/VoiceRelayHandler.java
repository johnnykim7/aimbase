package com.platform.speech.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.PlatformSettingsService;
import com.platform.domain.ConnectionEntity;
import com.platform.policy.AuditLogger;
import com.platform.speech.SttLocalConnections;
import com.platform.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * CR-186: 위젯 음성 대화의 실시간 받아쓰기 중계(PRD-582, BIZ-253). 위젯 웹소켓 하나와 받아쓰기 서버 웹소켓 하나를
 * 한 발화 동안 잇는다. 소리와 받아쓴 글은 저장하지 않는다.
 */
@Component
public class VoiceRelayHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceRelayHandler.class);

    public static final String KEY_MAX_UTTERANCE = "widget.voice.max-utterance-seconds";
    public static final String KEY_FINAL_TIMEOUT = "widget.voice.final-timeout-seconds";
    public static final String KEY_CONNECT_TIMEOUT = "widget.voice.connect-timeout-seconds";

    static final int CLOSE_TOO_LONG = 4408;
    static final int CLOSE_FULL = 4429;
    static final int CLOSE_UPSTREAM = 1011;
    static final String AUDIT_ACTION = "VOICE_STREAM";

    /** max-utterance 위에 더 기다리는 시간. 위젯이 상한에서 end 를 보내고 마지막 글이 오는 데 걸리는 몫이다. */
    private static final long UTTERANCE_GRACE_SECONDS = 5;
    private static final int BINARY_LIMIT_BYTES = 512 * 1024;
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final String ATTR_RELAY = "voice.relay";

    private final VoiceTicketService ticketService;
    private final VoiceRelayRegistry registry;
    private final SttLocalConnections sttConnections;
    private final SttStreamClient streamClient;
    private final PlatformSettingsService settings;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "voice-relay-timer");
        t.setDaemon(true);
        return t;
    });

    public VoiceRelayHandler(VoiceTicketService ticketService,
                             VoiceRelayRegistry registry,
                             SttLocalConnections sttConnections,
                             SttStreamClient streamClient,
                             PlatformSettingsService settings,
                             AuditLogger auditLogger,
                             ObjectMapper objectMapper) {
        this.ticketService = ticketService;
        this.registry = registry;
        this.sttConnections = sttConnections;
        this.streamClient = streamClient;
        this.settings = settings;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    void shutdown() {
        timers.shutdownNow();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Object reject = session.getAttributes().get(VoiceTicketHandshakeInterceptor.ATTR_REJECT);
        if (reject instanceof Integer code) {
            session.close(new CloseStatus(code));
            return;
        }
        VoiceTicket ticket = (VoiceTicket) session.getAttributes().get(VoiceTicketHandshakeInterceptor.ATTR_TICKET);
        if (ticket == null) {
            session.close(new CloseStatus(VoiceTicketHandshakeInterceptor.CLOSE_TICKET));
            return;
        }
        String key = VoiceRelayRegistry.key(ticket.tenantId(), ticket.sessionId());
        // 입장권을 준 뒤 다른 연결이 먼저 자리를 찼거나, 같은 세션의 중계가 아직 열려 있다.
        if (registry.tryOpen(key, ticketService.maxConcurrent()) != VoiceRelayRegistry.Open.OK) {
            session.close(new CloseStatus(CLOSE_FULL));
            return;
        }
        session.setBinaryMessageSizeLimit(BINARY_LIMIT_BYTES);
        Relay relay = new Relay(session, ticket, key);
        session.getAttributes().put(ATTR_RELAY, relay);
        relay.start();
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Relay relay = relay(session);
        if (relay == null) return;
        ByteBuffer payload = message.getPayload();
        byte[] chunk = new byte[payload.remaining()];
        payload.get(chunk);
        relay.audio(chunk);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Relay relay = relay(session);
        if (relay == null) return;
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            if ("end".equals(node.path("type").asText())) relay.end();
        } catch (Exception e) {
            log.debug("[CR-186] 위젯이 보낸 글을 읽지 못했다: {}", e.getMessage());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Relay relay = relay(session);
        if (relay != null) relay.finish(CLOSE_UPSTREAM, "client_error");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Relay relay = relay(session);
        if (relay == null) return;
        relay.finish(status.getCode(), "client_closed");
        relay.audit();
    }

    private static Relay relay(WebSocketSession session) {
        return (Relay) session.getAttributes().get(ATTR_RELAY);
    }

    private <T> T inTenant(String tenantId, Supplier<T> work) {
        String previous = TenantContext.getTenantId();
        TenantContext.setTenantId(tenantId);
        try {
            return work.get();
        } finally {
            if (previous == null) TenantContext.clear();
            else TenantContext.setTenantId(previous);
        }
    }

    /** 한 발화의 중계. 위젯 쪽 보내기는 여러 스레드에서 오므로 동시 보내기 장식으로 감싼다. */
    final class Relay implements SttStreamClient.Listener {
        private final WebSocketSession out;
        private final VoiceTicket ticket;
        private final String key;
        private final long startedNanos = System.nanoTime();
        private final AtomicLong audioBytes = new AtomicLong();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean audited = new AtomicBoolean();
        private final CompletableFuture<SttStreamClient.Stream> upstream = new CompletableFuture<>();
        private volatile String endReason;
        private volatile ScheduledFuture<?> utteranceTimer;
        private volatile ScheduledFuture<?> finalTimer;

        Relay(WebSocketSession session, VoiceTicket ticket, String key) {
            this.out = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BINARY_LIMIT_BYTES);
            this.ticket = ticket;
            this.key = key;
        }

        void start() {
            Optional<ConnectionEntity> conn = inTenant(ticket.tenantId(), () -> sttConnections.findById(ticket.connectionId()));
            Map<String, Object> cfg = conn.map(ConnectionEntity::getConfig).orElse(null);
            Object baseUrl = cfg == null ? null : cfg.get("base_url");
            if (baseUrl == null || String.valueOf(baseUrl).isBlank()) {
                log.warn("[CR-186] 받아쓰기 연결이 없거나 base_url 이 비었다: {}", ticket.connectionId());
                finish(CLOSE_UPSTREAM, "unavailable");
                return;
            }
            Duration connectTimeout = Duration.ofSeconds(Math.max(1L, settings.getLong(KEY_CONNECT_TIMEOUT, 30L)));
            streamClient.open(String.valueOf(baseUrl), SttLocalConnections.apiKey(cfg), ticket.sessionId(),
                            connectTimeout, this)
                    .whenComplete((stream, error) -> {
                        if (error != null) {
                            log.warn("[CR-186] 받아쓰기 서버에 잇지 못했다: {}", error.getMessage());
                            finish(CLOSE_UPSTREAM, "connect_failed");
                            return;
                        }
                        upstream.complete(stream);
                        if (finished.get()) {
                            stream.close();
                            return;
                        }
                        // 받아쓰기 서버가 준비되기를 기다린 시간은 발화가 아니므로 듣기 시작할 때부터 잰다.
                        long limit = Math.max(1L, settings.getLong(KEY_MAX_UTTERANCE, 60L)) + UTTERANCE_GRACE_SECONDS;
                        utteranceTimer = timers.schedule(() -> finish(CLOSE_TOO_LONG, "too_long"), limit, TimeUnit.SECONDS);
                        send("{\"type\":\"ready\"}");
                    });
        }

        void audio(byte[] chunk) {
            if (finished.get() || chunk.length == 0) return;
            audioBytes.addAndGet(chunk.length);
            upstream.thenAccept(s -> s.sendAudio(chunk));
        }

        void end() {
            if (finished.get() || finalTimer != null) return;
            upstream.thenAccept(SttStreamClient.Stream::sendEnd);
            long wait = Math.max(1L, settings.getLong(KEY_FINAL_TIMEOUT, 10L));
            finalTimer = timers.schedule(() -> finish(CLOSE_UPSTREAM, "final_timeout"), wait, TimeUnit.SECONDS);
        }

        @Override
        public void onText(String json) {
            if (finished.get()) return;
            String type;
            try {
                type = objectMapper.readTree(json).path("type").asText();
            } catch (Exception e) {
                log.debug("[CR-186] 받아쓰기 서버가 보낸 글을 읽지 못했다: {}", e.getMessage());
                return;
            }
            if ("transcript".equals(type)) {
                send(json);
            } else if ("final".equals(type)) {
                send(json);
                finish(CloseStatus.NORMAL.getCode(), "final");
            }
        }

        @Override
        public void onClosed(int code, String reason) {
            finish(CLOSE_UPSTREAM, "upstream_closed");
        }

        @Override
        public void onError(Throwable error) {
            log.warn("[CR-186] 받아쓰기 서버 연결 오류: {}", error.getMessage());
            finish(CLOSE_UPSTREAM, "upstream_error");
        }

        /** 한 번만 돈다. 다음 입장권이 바로 자리를 얻도록 위젯을 닫기 전에 자리부터 푼다. */
        void finish(int code, String reason) {
            if (!finished.compareAndSet(false, true)) return;
            endReason = reason;
            registry.close(key);
            cancel(utteranceTimer);
            cancel(finalTimer);
            upstream.thenAccept(SttStreamClient.Stream::close);
            try {
                if (out.isOpen()) out.close(new CloseStatus(code));
            } catch (Exception e) {
                log.debug("[CR-186] 위젯 웹소켓을 닫지 못했다: {}", e.getMessage());
            }
        }

        void audit() {
            if (!audited.compareAndSet(false, true)) return;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("duration_ms", (System.nanoTime() - startedNanos) / 1_000_000L);
            detail.put("audio_bytes", audioBytes.get());
            detail.put("end_reason", endReason);
            inTenant(ticket.tenantId(), () -> {
                auditLogger.log(AUDIT_ACTION, "voice_stream", ticket.userRef(), ticket.sessionId(), detail, null);
                return null;
            });
        }

        private void send(String json) {
            try {
                out.sendMessage(new TextMessage(json));
            } catch (Exception e) {
                log.debug("[CR-186] 위젯으로 보내지 못했다: {}", e.getMessage());
                finish(CLOSE_UPSTREAM, "client_send_failed");
            }
        }

        private void cancel(ScheduledFuture<?> f) {
            if (f != null) f.cancel(false);
        }
    }
}
