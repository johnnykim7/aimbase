package com.platform.mcp.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * CR-041 후속: TURN Allocate Request로 릴레이 주소를 획득.
 * RFC 5766 최소 구현 — Allocate(TCP 릴레이)만 사용.
 * HMAC-SHA1 임시 인증 (shared secret 기반).
 *
 * 흐름:
 * 1. Allocate Request (인증 없이) → 401 + nonce/realm
 * 2. Allocate Request (MESSAGE-INTEGRITY 포함) → 200 + XOR-RELAYED-ADDRESS
 */
public final class TurnRelayClient {

    private static final Logger log = LoggerFactory.getLogger(TurnRelayClient.class);

    private static final int TURN_TIMEOUT_MS = 5000;

    // STUN/TURN message types
    private static final short ALLOCATE_REQUEST = 0x0003;
    private static final short ALLOCATE_SUCCESS = 0x0103;
    private static final short ALLOCATE_ERROR = 0x0113;

    // STUN magic cookie (RFC 5389)
    private static final int MAGIC_COOKIE = 0x2112A442;

    // Attribute types
    private static final short ATTR_XOR_RELAYED_ADDRESS = 0x0016;
    private static final short ATTR_REQUESTED_TRANSPORT = 0x0019;
    private static final short ATTR_USERNAME = 0x0006;
    private static final short ATTR_REALM = 0x0014;
    private static final short ATTR_NONCE = 0x0015;
    private static final short ATTR_MESSAGE_INTEGRITY = 0x0008;

    // Transport: TCP (6), UDP (17)
    private static final int TRANSPORT_UDP = 17;

    private TurnRelayClient() {}

    /**
     * TURN 서버에 Allocate 요청하여 릴레이 주소(IP:port)를 획득한다.
     *
     * @param turnServer     TURN 서버 주소
     * @param turnPort       TURN 서버 포트
     * @param realm          인증 realm
     * @param sharedSecret   HMAC-SHA1 shared secret
     * @param agentName      에이전트 이름 (username 생성용)
     * @return 릴레이 주소 "IP:port" 또는 null (실패 시)
     */
    public static String allocateRelay(String turnServer, int turnPort,
                                        String realm, String sharedSecret,
                                        String agentName) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TURN_TIMEOUT_MS);
            InetAddress serverAddr = InetAddress.getByName(turnServer);
            InetSocketAddress serverSockAddr = new InetSocketAddress(serverAddr, turnPort);

            // Transaction ID (12 bytes, 재사용)
            byte[] txnId = generateTransactionId();

            // ── Step 1: 인증 없는 Allocate Request → 401 + nonce ──
            byte[] initialRequest = buildAllocateRequest(txnId, null, null, null, null);
            DatagramPacket sendPkt = new DatagramPacket(
                    initialRequest, initialRequest.length, serverSockAddr);
            socket.send(sendPkt);

            byte[] responseBuf = new byte[1024];
            DatagramPacket recvPkt = new DatagramPacket(responseBuf, responseBuf.length);
            socket.receive(recvPkt);

            ByteBuffer resp1 = ByteBuffer.wrap(responseBuf, 0, recvPkt.getLength());
            short msgType1 = resp1.getShort();
            short msgLen1 = resp1.getShort();
            resp1.getInt(); // magic cookie (skip)
            resp1.position(resp1.position() + 12); // skip txn ID

            // 401이면 nonce/realm 추출
            String nonce = null;
            String serverRealm = null;

            if (msgType1 == ALLOCATE_ERROR) {
                int endPos1 = 20 + msgLen1;
                while (resp1.position() < endPos1 && resp1.remaining() >= 4) {
                    short attrType = resp1.getShort();
                    short attrLen = resp1.getShort();

                    if (attrType == ATTR_NONCE) {
                        byte[] nonceBytes = new byte[attrLen];
                        resp1.get(nonceBytes);
                        nonce = new String(nonceBytes, StandardCharsets.UTF_8);
                        skipPadding(resp1, attrLen);
                    } else if (attrType == ATTR_REALM) {
                        byte[] realmBytes = new byte[attrLen];
                        resp1.get(realmBytes);
                        serverRealm = new String(realmBytes, StandardCharsets.UTF_8);
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
                // 인증 없이 성공 (테스트 환경 등)
                return extractRelayAddress(resp1, msgLen1);
            }

            if (nonce == null) {
                log.warn("TURN Allocate: no nonce received from {}:{}", turnServer, turnPort);
                return null;
            }

            String effectiveRealm = serverRealm != null ? serverRealm : realm;

            // ── Step 2: HMAC 인증 포함 Allocate Request ──
            // TURN HMAC-SHA1 임시 인증: username = "timestamp:agentName"
            long timestamp = System.currentTimeMillis() / 1000 + 86400; // +24h
            String username = timestamp + ":" + agentName;
            String password = generateHmacPassword(sharedSecret, username);

            byte[] txnId2 = generateTransactionId();
            byte[] authRequest = buildAllocateRequest(
                    txnId2, username, effectiveRealm, nonce,
                    computeKey(username, effectiveRealm, password));

            DatagramPacket sendPkt2 = new DatagramPacket(
                    authRequest, authRequest.length, serverSockAddr);
            socket.send(sendPkt2);

            byte[] responseBuf2 = new byte[1024];
            DatagramPacket recvPkt2 = new DatagramPacket(responseBuf2, responseBuf2.length);
            socket.receive(recvPkt2);

            ByteBuffer resp2 = ByteBuffer.wrap(responseBuf2, 0, recvPkt2.getLength());
            short msgType2 = resp2.getShort();
            short msgLen2 = resp2.getShort();
            resp2.getInt(); // magic cookie
            resp2.position(resp2.position() + 12); // skip txn ID

            if (msgType2 == ALLOCATE_SUCCESS) {
                String relayAddr = extractRelayAddress(resp2, msgLen2);
                if (relayAddr != null) {
                    log.info("TURN relay allocated: {} (via {}:{})", relayAddr, turnServer, turnPort);
                    return relayAddr;
                }
            }

            log.warn("TURN Allocate failed: response type=0x{}", Integer.toHexString(msgType2 & 0xFFFF));
            return null;

        } catch (Exception e) {
            log.warn("TURN Allocate failed ({}:{}): {}", turnServer, turnPort, e.getMessage());
            return null;
        }
    }

    /**
     * Allocate Request 패킷 빌드.
     */
    private static byte[] buildAllocateRequest(byte[] txnId,
                                                String username, String realm,
                                                String nonce, byte[] key) {
        // REQUESTED-TRANSPORT 속성 (4 bytes: protocol + 3 reserved)
        byte[] transportAttr = new byte[4];
        transportAttr[0] = (byte) TRANSPORT_UDP;

        // 속성들 크기 계산
        int attrSize = 4 + 4; // REQUESTED-TRANSPORT header + value

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
            attrSize += 4 + 20; // MESSAGE-INTEGRITY (HMAC-SHA1 = 20 bytes)
        } else {
            usernameBytes = null;
            realmBytes = null;
            nonceBytes = null;
        }

        ByteBuffer buf = ByteBuffer.allocate(20 + attrSize);

        // Header
        buf.putShort(ALLOCATE_REQUEST);
        buf.putShort((short) attrSize);
        buf.putInt(MAGIC_COOKIE);
        buf.put(txnId);

        // REQUESTED-TRANSPORT
        buf.putShort(ATTR_REQUESTED_TRANSPORT);
        buf.putShort((short) 4);
        buf.put(transportAttr);

        // 인증 속성
        if (hasAuth && usernameBytes != null && realmBytes != null && nonceBytes != null) {
            // USERNAME
            buf.putShort(ATTR_USERNAME);
            buf.putShort((short) usernameBytes.length);
            buf.put(usernameBytes);
            writePadding(buf, usernameBytes.length);

            // REALM
            buf.putShort(ATTR_REALM);
            buf.putShort((short) realmBytes.length);
            buf.put(realmBytes);
            writePadding(buf, realmBytes.length);

            // NONCE
            buf.putShort(ATTR_NONCE);
            buf.putShort((short) nonceBytes.length);
            buf.put(nonceBytes);
            writePadding(buf, nonceBytes.length);

            // MESSAGE-INTEGRITY (HMAC-SHA1 over everything before this attribute)
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

    /**
     * 응답에서 XOR-RELAYED-ADDRESS 추출.
     */
    private static String extractRelayAddress(ByteBuffer resp, short msgLen) {
        int endPos = resp.position() + msgLen;
        // position이 이미 속성 영역을 가리키고 있을 수 있으므로 재조정
        if (resp.position() < 20) resp.position(20);
        endPos = 20 + msgLen;

        while (resp.position() < endPos && resp.remaining() >= 4) {
            short attrType = resp.getShort();
            short attrLen = resp.getShort();

            if (attrType == ATTR_XOR_RELAYED_ADDRESS) {
                resp.get(); // reserved
                byte family = resp.get();
                short xorPort = resp.getShort();
                if (family == 0x01) { // IPv4
                    int xorAddr = resp.getInt();
                    int port = (xorPort ^ (short) (MAGIC_COOKIE >> 16)) & 0xFFFF;
                    int addr = xorAddr ^ MAGIC_COOKIE;
                    String ip = String.format("%d.%d.%d.%d",
                            (addr >> 24) & 0xFF, (addr >> 16) & 0xFF,
                            (addr >> 8) & 0xFF, addr & 0xFF);
                    return ip + ":" + port;
                }
            } else {
                int skip = attrLen + paddingSize(attrLen);
                if (resp.remaining() >= skip) {
                    resp.position(resp.position() + skip);
                } else {
                    break;
                }
            }
        }
        return null;
    }

    /**
     * HMAC-SHA1 임시 인증용 패스워드 생성.
     * password = Base64(HMAC-SHA1(sharedSecret, username))
     */
    private static String generateHmacPassword(String sharedSecret, String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] hmac = mac.doFinal(username.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmac);
        } catch (Exception e) {
            throw new RuntimeException("HMAC password generation failed", e);
        }
    }

    /**
     * MESSAGE-INTEGRITY용 키 = MD5(username:realm:password)
     */
    private static byte[] computeKey(String username, String realm, String password) {
        try {
            String material = username + ":" + realm + ":" + password;
            return MessageDigest.getInstance("MD5")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Key computation failed", e);
        }
    }

    /**
     * HMAC-SHA1 계산.
     */
    private static byte[] computeHmacSha1(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA1 computation failed", e);
        }
    }

    private static byte[] generateTransactionId() {
        byte[] txnId = new byte[12];
        for (int i = 0; i < 12; i++) {
            txnId[i] = (byte) (Math.random() * 256);
        }
        return txnId;
    }

    private static int paddingSize(int length) {
        int mod = length % 4;
        return mod == 0 ? 0 : 4 - mod;
    }

    private static void skipPadding(ByteBuffer buf, int attrLen) {
        int pad = paddingSize(attrLen);
        if (buf.remaining() >= pad) {
            buf.position(buf.position() + pad);
        }
    }

    private static void writePadding(ByteBuffer buf, int attrLen) {
        int pad = paddingSize(attrLen);
        for (int i = 0; i < pad; i++) {
            buf.put((byte) 0);
        }
    }
}
