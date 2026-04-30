package com.platform.mcp.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.platform.mcp.agent.TurnTcpAllocator.MAGIC_COOKIE;
import static com.platform.mcp.agent.TurnTcpAllocator.generateTransactionId;
import static com.platform.mcp.agent.TurnTcpAllocator.readStunMessage;

/**
 * CR-074: RFC 6062 §4.3 ConnectionAttempt 처리 + ConnectionBind 응답.
 * CR-076: 438 Stale Nonce 처리 + ConnectionBind error 1회 재시도.
 *
 * <p>{@link TurnTcpAllocator#allocate} 가 반환한 control socket 을 위에서 받아 동작한다.
 * <ol>
 *   <li>control socket 에서 STUN 메시지 수신 루프 진입</li>
 *   <li>{@code ConnectionAttempt indication (0x001C)} 수신 시 CONNECTION-ID 추출</li>
 *   <li>TURN 서버에 새 TCP socket 수립</li>
 *   <li>그 위로 {@code ConnectionBind request (0x000B)} 송신</li>
 *   <li>{@code ConnectionBind success (0x010B)} 수신 후 socket 을
 *       {@code acceptedSocketHandler} 콜백에 위임</li>
 *   <li>(CR-076) {@code ConnectionBind error (0x011B)} 수신 시 ERROR-CODE 검사 —
 *       438 이면 응답에 실린 NONCE 로 {@link TurnAuthSession} 갱신 후 1회 재시도</li>
 * </ol>
 *
 * <p>또한 receiveLoop 은 control socket 으로 들어오는 모든 응답에서 ERROR-CODE/NONCE 를
 * 검사하여 nonce 가 갱신되면 즉시 {@link TurnAuthSession} 에 반영한다 — Refresh /
 * CreatePermission 응답이 438 을 줘도 다음 ConnectionBind 가 새 nonce 로 나간다.
 *
 * <p>호출자(보통 {@link AgentLifecycle}) 는 콜백에서 그 socket 을 loopback bridge 로
 * Tomcat 의 RunnerController 에 그대로 흘려보낸다.
 */
public class TurnConnectionBindHandler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TurnConnectionBindHandler.class);

    // RFC 6062 message types
    static final short CONNECTION_ATTEMPT_INDICATION = 0x001C;
    static final short CONNECTION_BIND_REQUEST       = 0x000B;
    static final short CONNECTION_BIND_SUCCESS       = 0x010B;
    static final short CONNECTION_BIND_ERROR         = 0x011B;

    // Attribute types
    static final short ATTR_CONNECTION_ID = 0x002A;
    static final short ATTR_ERROR_CODE    = 0x0009;
    static final short ATTR_NONCE         = 0x0015;

    // RFC 5389 — STUN error codes
    static final int STALE_NONCE = 438;
    static final int UNAUTHORIZED = 401;

    private final String turnServer;
    private final int turnPort;
    private final Socket controlSocket;
    /** ConnectionBind 시 동일 인증 자료(username/realm/nonce/key) 가 필요. nonce 갱신을 위해 mutable. */
    private final TurnAuthSession authSession;
    /** ConnectionBind 까지 끝낸 socket 을 받아 처리할 콜백 (보통 loopback bridge 시작). */
    private final Consumer<Socket> acceptedSocketHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    /** CR-079 보강: receiveLoop 가 외부 RST/EOF 로 종료된 것을 외부에 알리는 플래그. */
    private final AtomicBoolean controlBroken = new AtomicBoolean(false);
    /** CR-079 보강 +1: ConnectionBind 가 연속 실패한 횟수. 임계 도달 시 broken signal. */
    private final java.util.concurrent.atomic.AtomicInteger connectionBindFailures = new java.util.concurrent.atomic.AtomicInteger(0);
    /** ConnectionBind 누적 실패가 이 임계 이상이면 control 측에 문제가 있다고 보고 reallocate. */
    private static final int BIND_FAILURE_THRESHOLD = 2;
    /** CR-079 보강: control socket broken 감지 즉시 호출되는 콜백 (AgentLifecycle 의 reallocate 트리거). */
    private volatile Runnable onControlBroken;
    private final ExecutorService bindExecutor;
    private Thread receiveLoop;

    public TurnConnectionBindHandler(String turnServer, int turnPort,
                                     Socket controlSocket,
                                     TurnAuthSession authSession,
                                     Consumer<Socket> acceptedSocketHandler) {
        this.turnServer = turnServer;
        this.turnPort = turnPort;
        this.controlSocket = controlSocket;
        this.authSession = authSession;
        this.acceptedSocketHandler = acceptedSocketHandler;
        this.bindExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "turn-bind");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 수신 루프 시작 (별도 스레드).
     */
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        receiveLoop = new Thread(this::receiveLoop, "turn-control-recv");
        receiveLoop.setDaemon(true);
        receiveLoop.start();
    }

    @Override
    public void close() {
        running.set(false);
        bindExecutor.shutdownNow();
        try { controlSocket.close(); } catch (IOException ignore) {}
        if (receiveLoop != null) {
            receiveLoop.interrupt();
        }
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    private void receiveLoop() {
        try {
            DataInputStream in = new DataInputStream(controlSocket.getInputStream());
            while (running.get()) {
                ByteBuffer msg;
                try {
                    msg = readStunMessage(in);
                } catch (SocketException se) {
                    // CR-079 보강: 외부 RST/EOF — controlBroken 으로 외부에 알림. AgentLifecycle.refresh tick 이 보고 reallocate.
                    if (running.get()) {
                        log.warn("TURN control socket closed: {}", se.getMessage());
                        signalBroken();
                    }
                    return;
                } catch (IOException ioe) {
                    if (running.get()) {
                        log.warn("TURN control read failed: {}", ioe.getMessage());
                        signalBroken();
                    }
                    return;
                }

                short msgType = msg.getShort();
                short msgLen  = msg.getShort();
                msg.getInt();        // magic cookie
                byte[] txn = new byte[12]; msg.get(txn);

                if (msgType == CONNECTION_ATTEMPT_INDICATION) {
                    int connectionId = extractConnectionId(msg, msgLen);
                    if (connectionId == -1) {
                        log.warn("ConnectionAttempt without CONNECTION-ID — skip");
                        continue;
                    }
                    log.info("ConnectionAttempt received: connection-id={}", connectionId);
                    bindExecutor.submit(() -> handleAttempt(connectionId));
                } else {
                    // CR-076: control socket 에 도착한 응답이 438 (Stale Nonce) 이면 새 nonce 추출 후 갱신.
                    // Allocate refresh / CreatePermission 응답이 여기로 옴. authSession=null (anonymous) 이면 무시.
                    int errorCode = (authSession != null) ? extractErrorCode(msg, msgLen) : -1;
                    if (errorCode == STALE_NONCE) {
                        String newNonce = extractNonce(msg, msgLen);
                        if (newNonce != null) {
                            authSession.updateNonce(newNonce);
                            log.info("TURN nonce refreshed via 438 response (type=0x{})",
                                    Integer.toHexString(msgType & 0xFFFF));
                        }
                    } else {
                        log.debug("TURN control msg (skipped): type=0x{}", Integer.toHexString(msgType & 0xFFFF));
                    }
                }
            }
        } catch (Exception e) {
            // CR-079 보강: 예외로 인한 종료도 broken 으로 신호.
            log.warn("TURN control receive loop failed: {}", e.getMessage(), e);
            if (running.get()) signalBroken();
        }
    }

    /** CR-079 보강: 외부에서 control socket 의 RST/EOF 감지 여부 확인. */
    public boolean isControlBroken() {
        return controlBroken.get();
    }

    /** CR-079 보강: broken 감지 즉시 실행될 콜백 등록 (한 번만 호출됨). */
    public void setOnControlBroken(Runnable cb) {
        this.onControlBroken = cb;
    }

    /** broken 신호와 콜백을 한 번에 처리 (중복 호출 방지). */
    private void signalBroken() {
        if (controlBroken.compareAndSet(false, true)) {
            Runnable cb = this.onControlBroken;
            if (cb != null) {
                try { cb.run(); } catch (Throwable t) { log.warn("onControlBroken callback failed: {}", t.getMessage()); }
            }
        }
    }

    private void handleAttempt(int connectionId) {
        boolean succeeded = false;
        try {
            succeeded = handleAttemptInner(connectionId);
        } finally {
            if (!succeeded) {
                // CR-079 보강 +1: ConnectionBind 가 모두 실패하면 누적. 임계 도달 시 broken signal.
                int failures = connectionBindFailures.incrementAndGet();
                if (failures >= BIND_FAILURE_THRESHOLD) {
                    log.warn("ConnectionBind 연속 실패 {}회 — broken signal 발행 (control 측 채널 회복 필요)", failures);
                    signalBroken();
                }
            }
        }
    }

    /**
     * ConnectionBind 흐름 본체. 성공 시 true 반환.
     */
    private boolean handleAttemptInner(int connectionId) {
        // CR-076: ConnectionBind error (0x011B) 시 ERROR-CODE 검사. 438 이면 새 nonce 추출 후 1회 재시도.
        int maxAttempts = 2;
        Socket dataSocket = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                dataSocket = new Socket();
                dataSocket.connect(new InetSocketAddress(turnServer, turnPort), 5000);
                dataSocket.setTcpNoDelay(true);
                dataSocket.setKeepAlive(true);

                OutputStream out = dataSocket.getOutputStream();
                DataInputStream in = new DataInputStream(dataSocket.getInputStream());

                byte[] txnId = generateTransactionId();
                TurnAuthMaterial auth = (authSession != null) ? authSession.current() : null;
                byte[] req = buildConnectionBindRequest(txnId, connectionId, auth);
                out.write(req);
                out.flush();

                ByteBuffer resp = readStunMessage(in);
                short type = resp.getShort();
                short respLen = resp.getShort();
                resp.getInt();       // magic
                byte[] respTxn = new byte[12]; resp.get(respTxn);

                if (type == CONNECTION_BIND_SUCCESS) {
                    log.info("ConnectionBind success: connection-id={} (attempt={})", connectionId, attempt);
                    dataSocket.setSoTimeout(0);
                    // CR-079 보강 +1: 성공 시 누적 실패 카운터 리셋.
                    connectionBindFailures.set(0);
                    acceptedSocketHandler.accept(dataSocket);
                    return true;
                }

                if (type == CONNECTION_BIND_ERROR) {
                    int errorCode = extractErrorCode(resp, respLen);
                    log.warn("ConnectionBind error: connection-id={} code={} (attempt={}/{})",
                            connectionId, errorCode, attempt, maxAttempts);
                    if (authSession != null
                            && (errorCode == STALE_NONCE || errorCode == UNAUTHORIZED)
                            && attempt < maxAttempts) {
                        String newNonce = extractNonce(resp, respLen);
                        if (newNonce != null) {
                            authSession.updateNonce(newNonce);
                            log.info("ConnectionBind retry with refreshed nonce");
                        }
                        try { dataSocket.close(); } catch (IOException ignore) {}
                        dataSocket = null;
                        continue;   // 새 socket 으로 재시도
                    }
                    try { dataSocket.close(); } catch (IOException ignore) {}
                    return false;
                }

                log.warn("ConnectionBind unexpected response: type=0x{}",
                        Integer.toHexString(type & 0xFFFF));
                try { dataSocket.close(); } catch (IOException ignore) {}
                return false;
            } catch (Exception e) {
                log.warn("ConnectionBind handling failed (id={}, attempt={}): {}",
                        connectionId, attempt, e.getMessage());
                if (dataSocket != null) {
                    try { dataSocket.close(); } catch (IOException ignore) {}
                    dataSocket = null;
                }
                // CR-079 보강 +1: 1차 실패 후 잠깐 대기 — 이 사이 control socket 의 receiveLoop 가
                // 다른 응답에서 nonce 를 갱신할 기회. 그러면 2차 시도가 fresh nonce 로 나간다.
                if (attempt < maxAttempts) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
                }
                if (attempt == maxAttempts) return false;
            }
        }
        return false;
    }

    static int extractConnectionId(ByteBuffer msg, short msgLen) {
        int endPos = 20 + msgLen;
        msg.position(20);
        while (msg.position() < endPos && msg.remaining() >= 4) {
            short attrType = msg.getShort();
            short attrLen  = msg.getShort();
            if (attrType == ATTR_CONNECTION_ID && attrLen == 4) {
                return msg.getInt();
            } else {
                int skip = attrLen + TurnTcpAllocator.paddingSize(attrLen);
                if (msg.remaining() >= skip) {
                    msg.position(msg.position() + skip);
                } else {
                    break;
                }
            }
        }
        return -1;
    }

    /**
     * RFC 5389 §15.6 ERROR-CODE attribute. value = 4 bytes 헤더(reserved + class + number) + reason phrase.
     * code = class*100 + number.
     */
    static int extractErrorCode(ByteBuffer msg, short msgLen) {
        int saved = msg.position();
        try {
            int endPos = 20 + msgLen;
            msg.position(20);
            while (msg.position() < endPos && msg.remaining() >= 4) {
                short attrType = msg.getShort();
                short attrLen = msg.getShort();
                if (attrType == ATTR_ERROR_CODE && attrLen >= 4) {
                    msg.getShort();                  // reserved (2 bytes)
                    int errClass = msg.get() & 0x07;  // 3 bits
                    int errNumber = msg.get() & 0xFF;
                    return errClass * 100 + errNumber;
                } else {
                    int skip = attrLen + TurnTcpAllocator.paddingSize(attrLen);
                    if (msg.remaining() >= skip) {
                        msg.position(msg.position() + skip);
                    } else {
                        break;
                    }
                }
            }
            return -1;
        } finally {
            msg.position(saved);
        }
    }

    static String extractNonce(ByteBuffer msg, short msgLen) {
        int saved = msg.position();
        try {
            int endPos = 20 + msgLen;
            msg.position(20);
            while (msg.position() < endPos && msg.remaining() >= 4) {
                short attrType = msg.getShort();
                short attrLen = msg.getShort();
                if (attrType == ATTR_NONCE) {
                    byte[] nb = new byte[attrLen];
                    msg.get(nb);
                    return new String(nb, StandardCharsets.UTF_8);
                } else {
                    int skip = attrLen + TurnTcpAllocator.paddingSize(attrLen);
                    if (msg.remaining() >= skip) {
                        msg.position(msg.position() + skip);
                    } else {
                        break;
                    }
                }
            }
            return null;
        } finally {
            msg.position(saved);
        }
    }

    static byte[] buildConnectionBindRequest(byte[] txnId, int connectionId, TurnAuthMaterial auth) {
        // CONNECTION-ID (4 bytes value) + 인증(있으면)
        byte[] usernameBytes = auth != null ? auth.usernameBytes() : null;
        byte[] realmBytes    = auth != null ? auth.realmBytes() : null;
        byte[] nonceBytes    = auth != null ? auth.nonceBytes() : null;
        boolean hasAuth = auth != null;

        int attrSize = 4 + 4; // CONNECTION-ID TLV
        if (hasAuth) {
            attrSize += 4 + usernameBytes.length + TurnTcpAllocator.paddingSize(usernameBytes.length);
            attrSize += 4 + realmBytes.length    + TurnTcpAllocator.paddingSize(realmBytes.length);
            attrSize += 4 + nonceBytes.length    + TurnTcpAllocator.paddingSize(nonceBytes.length);
            attrSize += 4 + 20; // MESSAGE-INTEGRITY
        }

        ByteBuffer buf = ByteBuffer.allocate(20 + attrSize);
        buf.putShort(CONNECTION_BIND_REQUEST);
        buf.putShort((short) attrSize);
        buf.putInt(MAGIC_COOKIE);
        buf.put(txnId);

        // CONNECTION-ID
        buf.putShort(ATTR_CONNECTION_ID);
        buf.putShort((short) 4);
        buf.putInt(connectionId);

        if (hasAuth) {
            putAttr(buf, TurnTcpAllocator.ATTR_USERNAME, usernameBytes);
            putAttr(buf, TurnTcpAllocator.ATTR_REALM,    realmBytes);
            putAttr(buf, TurnTcpAllocator.ATTR_NONCE,    nonceBytes);

            byte[] dataForIntegrity = new byte[buf.position()];
            buf.rewind();
            buf.get(dataForIntegrity);
            buf.position(dataForIntegrity.length);
            byte[] hmac = TurnTcpAllocator.computeHmacSha1(auth.key(), dataForIntegrity);

            buf.putShort(TurnTcpAllocator.ATTR_MESSAGE_INTEGRITY);
            buf.putShort((short) 20);
            buf.put(hmac);
        }

        byte[] result = new byte[buf.position()];
        buf.rewind();
        buf.get(result);
        return result;
    }

    private static void putAttr(ByteBuffer buf, short type, byte[] value) {
        buf.putShort(type);
        buf.putShort((short) value.length);
        buf.put(value);
        int pad = TurnTcpAllocator.paddingSize(value.length);
        for (int i = 0; i < pad; i++) buf.put((byte) 0);
    }
}
