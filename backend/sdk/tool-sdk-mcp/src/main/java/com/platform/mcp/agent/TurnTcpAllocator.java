package com.platform.mcp.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * CR-074: TURN over TCP (RFC 6062) Allocate 클라이언트.
 *
 * <p>UDP 전용 {@link TurnRelayClient} 와 다음 차이가 있다.
 * <ul>
 *   <li>전송이 TCP — control connection 을 long-lived 로 유지하고 그 위에서 TURN 메시지를 주고받는다</li>
 *   <li>{@code REQUESTED-TRANSPORT=6 (TCP)} — 외부 client 가 relay 주소로 TCP connect 가능</li>
 *   <li>Allocate 후에도 control connection 을 닫지 않는다. 호출자({@link TurnConnectionBindHandler}) 가 위임받아 사용</li>
 * </ul>
 *
 * <p>RFC 6062 흐름 중 Allocate 부분만 담당. ConnectionAttempt indication 처리는
 * {@link TurnConnectionBindHandler} 가 같은 socket 위에서 이어받는다.
 */
public final class TurnTcpAllocator {

    private static final Logger log = LoggerFactory.getLogger(TurnTcpAllocator.class);

    /** TCP control connection 최초 연결 timeout. */
    private static final int CONNECT_TIMEOUT_MS = 5000;
    /** Allocate 응답 read timeout (control connection lifetime 과 별개). */
    private static final int ALLOCATE_READ_TIMEOUT_MS = 10000;

    // STUN/TURN message types
    static final short ALLOCATE_REQUEST           = 0x0003;
    static final short ALLOCATE_SUCCESS           = 0x0103;
    static final short ALLOCATE_ERROR             = 0x0113;
    static final short CREATE_PERMISSION_REQUEST  = 0x0008;
    static final short CREATE_PERMISSION_SUCCESS  = 0x0108;
    static final short CREATE_PERMISSION_ERROR    = 0x0118;
    static final short REFRESH_REQUEST            = 0x0004;
    static final short REFRESH_SUCCESS            = 0x0104;

    // STUN magic cookie (RFC 5389)
    static final int MAGIC_COOKIE = 0x2112A442;

    // Attribute types
    static final short ATTR_XOR_RELAYED_ADDRESS = 0x0016;
    static final short ATTR_REQUESTED_TRANSPORT = 0x0019;
    static final short ATTR_USERNAME            = 0x0006;
    static final short ATTR_REALM               = 0x0014;
    static final short ATTR_NONCE               = 0x0015;
    static final short ATTR_MESSAGE_INTEGRITY   = 0x0008;
    static final short ATTR_LIFETIME            = 0x000D;
    static final short ATTR_XOR_PEER_ADDRESS    = 0x0012;

    // Transport: TCP (6), UDP (17)
    static final int TRANSPORT_TCP = 6;

    private TurnTcpAllocator() {}

    /**
     * Allocate 결과 — control socket(open 상태) + relay 주소 + 재사용 가능한 인증 자료.
     *
     * <p>호출자는 control socket 을 close 할 책임이 있다 (보통 {@link TurnConnectionBindHandler}
     * 가 close 시 함께 정리).
     *
     * <p>CR-076: authSession 은 mutable holder. 438 Stale Nonce 응답을 받으면
     * {@link TurnConnectionBindHandler} 가 새 nonce 로 갱신한다.
     * anonymous 허용 서버에서는 null.
     */
    public record Allocation(
            Socket controlSocket,
            String relayAddress,
            int lifetimeSeconds,
            TurnAuthSession authSession
    ) {}

    /**
     * TURN 서버에 TCP allocate 한다.
     *
     * @param turnServer    TURN 서버 host
     * @param turnPort      TURN 서버 port
     * @param realm         인증 realm (서버가 다른 값을 주면 그걸 우선 사용)
     * @param sharedSecret  HMAC-SHA1 임시 인증 shared secret
     * @param agentName     username 시드
     * @return Allocation (control socket open 상태) 또는 null (실패 시)
     */
    public static Allocation allocate(String turnServer, int turnPort,
                                      String realm, String sharedSecret,
                                      String agentName) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(turnServer, turnPort), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(ALLOCATE_READ_TIMEOUT_MS);
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);

            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // ── Step 1: 인증 없는 Allocate Request → 401 + nonce/realm ──
            byte[] txnId1 = generateTransactionId();
            byte[] req1 = buildAllocateRequest(txnId1, null, null, null, null);
            out.write(req1);
            out.flush();

            ByteBuffer resp1 = readStunMessage(in);
            short msgType1 = resp1.getShort();
            short msgLen1 = resp1.getShort();
            resp1.getInt();                // magic cookie
            byte[] respTxn1 = new byte[12];
            resp1.get(respTxn1);

            String nonce = null;
            String serverRealm = null;

            if (msgType1 == ALLOCATE_ERROR) {
                int endPos = resp1.position() + msgLen1;
                while (resp1.position() < endPos && resp1.remaining() >= 4) {
                    short attrType = resp1.getShort();
                    short attrLen = resp1.getShort();
                    if (attrType == ATTR_NONCE) {
                        byte[] nb = new byte[attrLen];
                        resp1.get(nb);
                        nonce = new String(nb, StandardCharsets.UTF_8);
                        skipPadding(resp1, attrLen);
                    } else if (attrType == ATTR_REALM) {
                        byte[] rb = new byte[attrLen];
                        resp1.get(rb);
                        serverRealm = new String(rb, StandardCharsets.UTF_8);
                        skipPadding(resp1, attrLen);
                    } else {
                        int skip = attrLen + paddingSize(attrLen);
                        if (resp1.remaining() >= skip) {
                            resp1.position(resp1.position() + skip);
                        } else {
                            break;
                        }
                    }
                }
            } else if (msgType1 == ALLOCATE_SUCCESS) {
                // 인증 없이 성공 (테스트용 — anonymous 허용 서버)
                String relay = extractRelayAddress(resp1, msgLen1);
                int lifetime = extractLifetime(resp1, msgLen1);
                socket.setSoTimeout(0);
                return new Allocation(socket, relay, lifetime, null);
            }

            if (nonce == null) {
                log.warn("TURN-TCP allocate: no nonce in initial response from {}:{}", turnServer, turnPort);
                safeClose(socket);
                return null;
            }
            String effectiveRealm = serverRealm != null ? serverRealm : realm;

            // ── Step 2: HMAC 인증 포함 Allocate Request ──
            long timestamp = System.currentTimeMillis() / 1000 + 86400; // +24h
            String username = timestamp + ":" + agentName;
            String password = generateHmacPassword(sharedSecret, username);
            byte[] key = computeKey(username, effectiveRealm, password);

            byte[] txnId2 = generateTransactionId();
            byte[] req2 = buildAllocateRequest(txnId2, username, effectiveRealm, nonce, key);
            out.write(req2);
            out.flush();

            ByteBuffer resp2 = readStunMessage(in);
            short msgType2 = resp2.getShort();
            short msgLen2 = resp2.getShort();
            resp2.getInt();
            byte[] respTxn2 = new byte[12];
            resp2.get(respTxn2);

            if (msgType2 == ALLOCATE_SUCCESS) {
                String relayAddr = extractRelayAddress(resp2, msgLen2);
                int lifetime = extractLifetime(resp2, msgLen2);
                if (relayAddr != null) {
                    log.info("TURN-TCP allocate success: relay={} lifetime={}s (via {}:{})",
                            relayAddr, lifetime, turnServer, turnPort);
                    socket.setSoTimeout(0); // long-lived control connection — block-read
                    TurnAuthSession authSession = TurnAuthSession.of(username, effectiveRealm, nonce, key);
                    return new Allocation(socket, relayAddr, lifetime, authSession);
                }
            }

            log.warn("TURN-TCP allocate failed: response type=0x{}",
                    Integer.toHexString(msgType2 & 0xFFFF));
            safeClose(socket);
            return null;

        } catch (Exception e) {
            log.warn("TURN-TCP allocate failed ({}:{}): {}", turnServer, turnPort, e.getMessage());
            safeClose(socket);
            return null;
        }
    }

    // ── packet build ─────────────────────────────────────────────────────

    static byte[] buildAllocateRequest(byte[] txnId, String username, String realm,
                                       String nonce, byte[] key) {
        // REQUESTED-TRANSPORT (4 bytes value: protocol + 3 reserved)
        byte[] transportAttr = new byte[4];
        transportAttr[0] = (byte) TRANSPORT_TCP;

        int attrSize = 4 + 4; // REQUESTED-TRANSPORT TLV

        final byte[] usernameBytes;
        final byte[] realmBytes;
        final byte[] nonceBytes;
        boolean hasAuth = username != null && realm != null && nonce != null;
        if (hasAuth) {
            usernameBytes = username.getBytes(StandardCharsets.UTF_8);
            realmBytes = realm.getBytes(StandardCharsets.UTF_8);
            nonceBytes = nonce.getBytes(StandardCharsets.UTF_8);
            attrSize += 4 + usernameBytes.length + paddingSize(usernameBytes.length);
            attrSize += 4 + realmBytes.length + paddingSize(realmBytes.length);
            attrSize += 4 + nonceBytes.length + paddingSize(nonceBytes.length);
            attrSize += 4 + 20; // MESSAGE-INTEGRITY
        } else {
            usernameBytes = null;
            realmBytes = null;
            nonceBytes = null;
        }

        ByteBuffer buf = ByteBuffer.allocate(20 + attrSize);
        buf.putShort(ALLOCATE_REQUEST);
        buf.putShort((short) attrSize);
        buf.putInt(MAGIC_COOKIE);
        buf.put(txnId);

        // REQUESTED-TRANSPORT
        buf.putShort(ATTR_REQUESTED_TRANSPORT);
        buf.putShort((short) 4);
        buf.put(transportAttr);

        if (hasAuth) {
            putAttr(buf, ATTR_USERNAME, usernameBytes);
            putAttr(buf, ATTR_REALM, realmBytes);
            putAttr(buf, ATTR_NONCE, nonceBytes);

            byte[] dataForIntegrity = new byte[buf.position()];
            buf.rewind();
            buf.get(dataForIntegrity);
            buf.position(dataForIntegrity.length);
            byte[] hmac = computeHmacSha1(key, dataForIntegrity);

            buf.putShort(ATTR_MESSAGE_INTEGRITY);
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
        int pad = paddingSize(value.length);
        for (int i = 0; i < pad; i++) buf.put((byte) 0);
    }

    // ── packet read ──────────────────────────────────────────────────────

    /**
     * RFC 6062 §6.2 — TCP 위에서는 STUN 메시지 framing 이 raw 바이트 그대로다.
     * 첫 4바이트(type+length) 를 읽고 length+padding(=length 자체) 만큼 더 읽으면 한 메시지.
     */
    static ByteBuffer readStunMessage(DataInputStream in) throws IOException {
        byte[] header = in.readNBytes(20);
        if (header.length < 20) throw new IOException("STUN header truncated: " + header.length);
        int msgLen = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        byte[] body = msgLen > 0 ? in.readNBytes(msgLen) : new byte[0];
        if (body.length < msgLen) throw new IOException("STUN body truncated: " + body.length + "/" + msgLen);
        ByteBuffer buf = ByteBuffer.allocate(20 + msgLen);
        buf.put(header);
        buf.put(body);
        buf.rewind();
        return buf;
    }

    static String extractRelayAddress(ByteBuffer resp, short msgLen) {
        int saved = resp.position();
        resp.position(20);
        int endPos = 20 + msgLen;
        while (resp.position() < endPos && resp.remaining() >= 4) {
            short attrType = resp.getShort();
            short attrLen = resp.getShort();
            if (attrType == ATTR_XOR_RELAYED_ADDRESS) {
                resp.get();                  // reserved
                byte family = resp.get();
                short xorPort = resp.getShort();
                if (family == 0x01) {        // IPv4
                    int xorAddr = resp.getInt();
                    int port = (xorPort ^ (short) (MAGIC_COOKIE >> 16)) & 0xFFFF;
                    int addr = xorAddr ^ MAGIC_COOKIE;
                    String ip = String.format("%d.%d.%d.%d",
                            (addr >> 24) & 0xFF, (addr >> 16) & 0xFF,
                            (addr >> 8) & 0xFF, addr & 0xFF);
                    resp.position(saved);
                    return ip + ":" + port;
                }
                resp.position(saved);
                return null;
            } else {
                int skip = attrLen + paddingSize(attrLen);
                if (resp.remaining() >= skip) {
                    resp.position(resp.position() + skip);
                } else {
                    break;
                }
            }
        }
        resp.position(saved);
        return null;
    }

    static int extractLifetime(ByteBuffer resp, short msgLen) {
        int saved = resp.position();
        resp.position(20);
        int endPos = 20 + msgLen;
        while (resp.position() < endPos && resp.remaining() >= 4) {
            short attrType = resp.getShort();
            short attrLen = resp.getShort();
            if (attrType == ATTR_LIFETIME && attrLen == 4) {
                int lifetime = resp.getInt();
                resp.position(saved);
                return lifetime;
            } else {
                int skip = attrLen + paddingSize(attrLen);
                if (resp.remaining() >= skip) {
                    resp.position(resp.position() + skip);
                } else {
                    break;
                }
            }
        }
        resp.position(saved);
        return 600; // RFC 5766 권장 기본값
    }

    // ── CreatePermission / Refresh (RFC 5766 §9, §7) ────────────────────

    /**
     * RFC 5766 §9 — control connection 위로 CreatePermission 송신.
     * peer IP 를 화이트리스트에 등록해서 incoming TCP 가 ConnectionAttempt 까지 가도록 한다.
     *
     * @param controlSocket Allocate 로 받은 long-lived TCP socket
     * @param peerIp        허용할 peer (외부 client) IP, 예: "203.0.113.7"
     * @param auth          Allocate 인증 자료 재사용
     */
    public static boolean sendCreatePermission(Socket controlSocket, String peerIp,
                                               TurnAuthMaterial auth) {
        try {
            byte[] txnId = generateTransactionId();
            byte[] req = buildCreatePermissionRequest(txnId, peerIp, auth);
            controlSocket.getOutputStream().write(req);
            controlSocket.getOutputStream().flush();
            // CreatePermission 응답 (success=0x0108) 은 BindHandler 의 receiveLoop 가 받아 무시.
            // 별도 동기 read 하지 않음 — receiveLoop 와 race 위험 회피.
            log.info("CreatePermission sent: peer={}", peerIp);
            return true;
        } catch (Exception e) {
            log.warn("CreatePermission failed (peer={}): {}", peerIp, e.getMessage());
            return false;
        }
    }

    /**
     * CR-076: TurnAuthSession 오버로드 — 매 호출 시 최신 nonce 사용.
     */
    public static boolean sendCreatePermission(Socket controlSocket, String peerIp,
                                               TurnAuthSession session) {
        return sendCreatePermission(controlSocket, peerIp,
                session != null ? session.current() : null);
    }

    /**
     * RFC 5766 §7 — Allocate lifetime refresh.
     * 기본 lifetime 600s 만료 전에 주기적으로 호출.
     */
    public static boolean sendRefresh(Socket controlSocket, int lifetimeSeconds,
                                       TurnAuthMaterial auth) {
        try {
            byte[] txnId = generateTransactionId();
            byte[] req = buildRefreshRequest(txnId, lifetimeSeconds, auth);
            controlSocket.getOutputStream().write(req);
            controlSocket.getOutputStream().flush();
            log.debug("Refresh sent: lifetime={}s", lifetimeSeconds);
            return true;
        } catch (Exception e) {
            log.warn("Refresh failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * CR-076: TurnAuthSession 오버로드 — 매 호출 시 최신 nonce 사용.
     */
    public static boolean sendRefresh(Socket controlSocket, int lifetimeSeconds,
                                      TurnAuthSession session) {
        return sendRefresh(controlSocket, lifetimeSeconds,
                session != null ? session.current() : null);
    }

    static byte[] buildCreatePermissionRequest(byte[] txnId, String peerIp, TurnAuthMaterial auth) {
        // body: XOR-PEER-ADDRESS TLV (4 + 8) + 인증 4종 (있을 때)
        ByteBuffer peer = ByteBuffer.allocate(8);
        peer.put((byte) 0);
        peer.put((byte) 0x01); // family IPv4
        peer.putShort((short) 0); // port = 0 (의미상 모든 port; coturn 은 IP 만 매칭)
        // XOR addr
        int addr = ipToInt(peerIp);
        peer.putInt(addr ^ MAGIC_COOKIE);

        return buildAuthenticatedRequest(CREATE_PERMISSION_REQUEST, txnId, auth, buf -> {
            buf.putShort(ATTR_XOR_PEER_ADDRESS);
            buf.putShort((short) 8);
            buf.put(peer.array());
        });
    }

    static byte[] buildRefreshRequest(byte[] txnId, int lifetime, TurnAuthMaterial auth) {
        return buildAuthenticatedRequest(REFRESH_REQUEST, txnId, auth, buf -> {
            buf.putShort(ATTR_LIFETIME);
            buf.putShort((short) 4);
            buf.putInt(lifetime);
        });
    }

    /**
     * 인증 포함 STUN 메시지 빌더 — Allocate 외 method 들도 동일 패턴이라 재사용.
     */
    private static byte[] buildAuthenticatedRequest(short messageType, byte[] txnId,
                                                     TurnAuthMaterial auth,
                                                     java.util.function.Consumer<ByteBuffer> bodyWriter) {
        // 1차 — body 크기 산출용 dummy buffer
        ByteBuffer probe = ByteBuffer.allocate(1024);
        bodyWriter.accept(probe);
        int bodyAttrSize = probe.position();

        int authAttrSize = 0;
        if (auth != null) {
            authAttrSize += 4 + auth.usernameBytes().length + paddingSize(auth.usernameBytes().length);
            authAttrSize += 4 + auth.realmBytes().length    + paddingSize(auth.realmBytes().length);
            authAttrSize += 4 + auth.nonceBytes().length    + paddingSize(auth.nonceBytes().length);
            authAttrSize += 4 + 20; // MESSAGE-INTEGRITY
        }
        int totalAttrSize = bodyAttrSize + authAttrSize;

        ByteBuffer buf = ByteBuffer.allocate(20 + totalAttrSize);
        buf.putShort(messageType);
        buf.putShort((short) totalAttrSize);
        buf.putInt(MAGIC_COOKIE);
        buf.put(txnId);

        bodyWriter.accept(buf);

        if (auth != null) {
            putAttr(buf, ATTR_USERNAME, auth.usernameBytes());
            putAttr(buf, ATTR_REALM,    auth.realmBytes());
            putAttr(buf, ATTR_NONCE,    auth.nonceBytes());

            byte[] dataForIntegrity = new byte[buf.position()];
            buf.rewind();
            buf.get(dataForIntegrity);
            buf.position(dataForIntegrity.length);
            byte[] hmac = computeHmacSha1(auth.key(), dataForIntegrity);

            buf.putShort(ATTR_MESSAGE_INTEGRITY);
            buf.putShort((short) 20);
            buf.put(hmac);
        }

        byte[] result = new byte[buf.position()];
        buf.rewind();
        buf.get(result);
        return result;
    }

    private static int ipToInt(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4: " + ip);
        return ((Integer.parseInt(parts[0]) & 0xFF) << 24)
             | ((Integer.parseInt(parts[1]) & 0xFF) << 16)
             | ((Integer.parseInt(parts[2]) & 0xFF) << 8)
             |  (Integer.parseInt(parts[3]) & 0xFF);
    }

    // ── auth helpers ─────────────────────────────────────────────────────

    static String generateHmacPassword(String sharedSecret, String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] hmac = mac.doFinal(username.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmac);
        } catch (Exception e) {
            throw new RuntimeException("HMAC password generation failed", e);
        }
    }

    static byte[] computeKey(String username, String realm, String password) {
        try {
            String material = username + ":" + realm + ":" + password;
            return MessageDigest.getInstance("MD5").digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Key computation failed", e);
        }
    }

    static byte[] computeHmacSha1(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA1 failed", e);
        }
    }

    static byte[] generateTransactionId() {
        byte[] txn = new byte[12];
        new SecureRandom().nextBytes(txn);
        return txn;
    }

    static int paddingSize(int length) {
        int mod = length % 4;
        return mod == 0 ? 0 : 4 - mod;
    }

    static void skipPadding(ByteBuffer buf, int attrLen) {
        int pad = paddingSize(attrLen);
        if (buf.remaining() >= pad) buf.position(buf.position() + pad);
    }

    private static void safeClose(Socket s) {
        if (s != null) {
            try { s.close(); } catch (IOException ignore) {}
        }
    }
}
