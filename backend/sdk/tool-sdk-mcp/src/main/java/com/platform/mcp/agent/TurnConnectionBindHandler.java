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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.platform.mcp.agent.TurnTcpAllocator.MAGIC_COOKIE;
import static com.platform.mcp.agent.TurnTcpAllocator.generateTransactionId;
import static com.platform.mcp.agent.TurnTcpAllocator.readStunMessage;

/**
 * CR-074: RFC 6062 §4.3 ConnectionAttempt 처리 + ConnectionBind 응답.
 *
 * <p>{@link TurnTcpAllocator#allocate} 가 반환한 control socket 을 위에서 받아 동작한다.
 * <ol>
 *   <li>control socket 에서 STUN 메시지 수신 루프 진입</li>
 *   <li>{@code ConnectionAttempt indication (0x001C)} 수신 시 CONNECTION-ID 추출</li>
 *   <li>TURN 서버에 새 TCP socket 수립</li>
 *   <li>그 위로 {@code ConnectionBind request (0x000B)} 송신</li>
 *   <li>{@code ConnectionBind success (0x010B)} 수신 후 socket 을
 *       {@code acceptedSocketHandler} 콜백에 위임</li>
 * </ol>
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

    private final String turnServer;
    private final int turnPort;
    private final Socket controlSocket;
    /** ConnectionBind 시 동일 인증 자료(username/realm/nonce/key) 가 필요하다. */
    private final TurnAuthMaterial authMaterial;
    /** ConnectionBind 까지 끝낸 socket 을 받아 처리할 콜백 (보통 loopback bridge 시작). */
    private final Consumer<Socket> acceptedSocketHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService bindExecutor;
    private Thread receiveLoop;

    public TurnConnectionBindHandler(String turnServer, int turnPort,
                                     Socket controlSocket,
                                     TurnAuthMaterial authMaterial,
                                     Consumer<Socket> acceptedSocketHandler) {
        this.turnServer = turnServer;
        this.turnPort = turnPort;
        this.controlSocket = controlSocket;
        this.authMaterial = authMaterial;
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
                    if (running.get()) log.warn("TURN control socket closed: {}", se.getMessage());
                    return;
                } catch (IOException ioe) {
                    if (running.get()) log.warn("TURN control read failed: {}", ioe.getMessage());
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
                    // refresh / data 등 — 본 핸들러에서는 무시 (refresh 는 별도 스케줄러로 보낼 예정 — 후속)
                    log.debug("TURN control msg (skipped): type=0x{}", Integer.toHexString(msgType & 0xFFFF));
                }
            }
        } catch (Exception e) {
            log.warn("TURN control receive loop failed: {}", e.getMessage(), e);
        }
    }

    private void handleAttempt(int connectionId) {
        Socket dataSocket = null;
        try {
            dataSocket = new Socket();
            dataSocket.connect(new InetSocketAddress(turnServer, turnPort), 5000);
            dataSocket.setTcpNoDelay(true);
            dataSocket.setKeepAlive(true);

            OutputStream out = dataSocket.getOutputStream();
            DataInputStream in = new DataInputStream(dataSocket.getInputStream());

            byte[] txnId = generateTransactionId();
            byte[] req = buildConnectionBindRequest(txnId, connectionId, authMaterial);
            out.write(req);
            out.flush();

            ByteBuffer resp = readStunMessage(in);
            short type = resp.getShort();
            resp.getShort();     // length
            resp.getInt();       // magic
            byte[] respTxn = new byte[12]; resp.get(respTxn);

            if (type == CONNECTION_BIND_SUCCESS) {
                log.info("ConnectionBind success: connection-id={} → handing socket to handler", connectionId);
                dataSocket.setSoTimeout(0);
                acceptedSocketHandler.accept(dataSocket);
                return;
            }

            log.warn("ConnectionBind failed: connection-id={} type=0x{}",
                    connectionId, Integer.toHexString(type & 0xFFFF));
            try { dataSocket.close(); } catch (IOException ignore) {}
        } catch (Exception e) {
            log.warn("ConnectionBind handling failed (id={}): {}", connectionId, e.getMessage());
            if (dataSocket != null) {
                try { dataSocket.close(); } catch (IOException ignore) {}
            }
        }
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
