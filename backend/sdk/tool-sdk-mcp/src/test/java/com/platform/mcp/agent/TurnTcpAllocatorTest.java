package com.platform.mcp.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-074: TurnTcpAllocator 단위 테스트.
 *
 * <p>실제 coturn 없이 검증하기 위해 raw TCP ServerSocket 으로 fake TURN 을 띄운다.
 * STUN 메시지 framing 만 흉내내고, attribute 파싱은 Allocator 의 정적 메서드를 직접 호출.
 */
class TurnTcpAllocatorTest {

    private ServerSocket fakeTurn;
    private ExecutorService executor;

    @AfterEach
    void tearDown() throws IOException {
        if (executor != null) executor.shutdownNow();
        if (fakeTurn != null && !fakeTurn.isClosed()) fakeTurn.close();
    }

    @Test
    void buildAllocateRequest_unauthenticated_setsTransportTcp() {
        byte[] txn = new byte[12];
        for (int i = 0; i < 12; i++) txn[i] = (byte) i;

        byte[] req = TurnTcpAllocator.buildAllocateRequest(txn, null, null, null, null);

        // Header (20 bytes)
        ByteBuffer buf = ByteBuffer.wrap(req);
        assertThat(buf.getShort()).as("ALLOCATE Request type").isEqualTo(TurnTcpAllocator.ALLOCATE_REQUEST);
        short bodyLen = buf.getShort();
        assertThat(buf.getInt()).as("STUN magic cookie").isEqualTo(TurnTcpAllocator.MAGIC_COOKIE);
        byte[] readTxn = new byte[12]; buf.get(readTxn);
        assertThat(readTxn).isEqualTo(txn);

        // Body — REQUESTED-TRANSPORT only
        assertThat(bodyLen).isEqualTo((short) 8); // TLV header (4) + value (4)
        assertThat(buf.getShort()).isEqualTo(TurnTcpAllocator.ATTR_REQUESTED_TRANSPORT);
        assertThat(buf.getShort()).isEqualTo((short) 4);
        assertThat(buf.get() & 0xFF).as("transport protocol").isEqualTo(TurnTcpAllocator.TRANSPORT_TCP);
        // 3 reserved zero bytes
        assertThat(buf.get()).isZero();
        assertThat(buf.get()).isZero();
        assertThat(buf.get()).isZero();
    }

    @Test
    void buildAllocateRequest_authenticated_appendsCredentialAndIntegrity() {
        byte[] txn = new byte[12];
        byte[] key = new byte[16]; // 16-byte MD5 key
        byte[] req = TurnTcpAllocator.buildAllocateRequest(
                txn, "1234:agent", "turnpike.local", "nonce-abc", key);

        // 메시지 길이가 헤더 외에 USERNAME / REALM / NONCE / MESSAGE-INTEGRITY 까지 포함
        assertThat(req.length).isGreaterThan(20 + 8); // 최소 unauth 길이보다 큼
        ByteBuffer buf = ByteBuffer.wrap(req);
        buf.position(2);
        short bodyLen = buf.getShort();
        assertThat(bodyLen).isGreaterThan((short) 8);

        // 마지막 24바이트가 MESSAGE-INTEGRITY TLV (4 + 20)
        ByteBuffer last = ByteBuffer.wrap(req, req.length - 24, 24);
        assertThat(last.getShort()).isEqualTo(TurnTcpAllocator.ATTR_MESSAGE_INTEGRITY);
        assertThat(last.getShort()).isEqualTo((short) 20);
    }

    @Test
    void readStunMessage_returnsHeaderPlusBody() throws IOException {
        // 가짜 메시지: type=0x0103, length=8, magic, txn, REQUESTED-TRANSPORT TLV
        ByteBuffer fake = ByteBuffer.allocate(28);
        fake.putShort(TurnTcpAllocator.ALLOCATE_SUCCESS);
        fake.putShort((short) 8);
        fake.putInt(TurnTcpAllocator.MAGIC_COOKIE);
        for (int i = 0; i < 12; i++) fake.put((byte) i);
        fake.putShort(TurnTcpAllocator.ATTR_REQUESTED_TRANSPORT);
        fake.putShort((short) 4);
        fake.putInt(0);
        byte[] payload = fake.array();

        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(payload));
        ByteBuffer parsed = TurnTcpAllocator.readStunMessage(in);
        assertThat(parsed.capacity()).isEqualTo(28);
        assertThat(parsed.getShort()).isEqualTo(TurnTcpAllocator.ALLOCATE_SUCCESS);
        assertThat(parsed.getShort()).isEqualTo((short) 8);
    }

    @Test
    void allocate_anonymousServer_returnsRelayWithoutAuth() throws IOException {
        fakeTurn = new ServerSocket(0); // 임의 포트
        int port = fakeTurn.getLocalPort();
        executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> handleAnonymousAllocate(fakeTurn));

        TurnTcpAllocator.Allocation alloc = TurnTcpAllocator.allocate(
                "127.0.0.1", port, "test.realm", "secret", "test-agent");

        assertThat(alloc).isNotNull();
        assertThat(alloc.relayAddress()).isEqualTo("203.0.113.7:50000");
        assertThat(alloc.lifetimeSeconds()).isEqualTo(600);
        assertThat(alloc.controlSocket().isClosed()).isFalse();
        assertThat(alloc.authMaterial()).isNull();
        alloc.controlSocket().close();
    }

    /**
     * Anonymous TURN — 첫 Allocate 에 바로 Success 응답 (XOR-RELAYED-ADDRESS 포함).
     */
    private static void handleAnonymousAllocate(ServerSocket server) {
        try (Socket conn = server.accept()) {
            DataInputStream in = new DataInputStream(conn.getInputStream());
            OutputStream out = conn.getOutputStream();

            // 요청 한 개 읽기
            ByteBuffer req = TurnTcpAllocator.readStunMessage(in);
            req.position(8);
            byte[] txn = new byte[12];
            req.get(txn);

            // 응답 빌드: ALLOCATE_SUCCESS + XOR-RELAYED-ADDRESS (203.0.113.7:50000)
            byte[] body = buildXorRelayedAddress("203.0.113.7", 50000);
            ByteBuffer resp = ByteBuffer.allocate(20 + body.length);
            resp.putShort(TurnTcpAllocator.ALLOCATE_SUCCESS);
            resp.putShort((short) body.length);
            resp.putInt(TurnTcpAllocator.MAGIC_COOKIE);
            resp.put(txn);
            resp.put(body);
            out.write(resp.array());
            out.flush();
            // 클라이언트가 control socket 을 들고 있으므로 즉시 종료하지 않음 — 그냥 메서드 종료
            try { Thread.sleep(50); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
        } catch (IOException e) {
            // 정상 종료
        }
    }

    /** XOR-RELAYED-ADDRESS TLV 만 빌드 (IPv4). */
    static byte[] buildXorRelayedAddress(String ip, int port) {
        // family=01 (IPv4), value=8 bytes (1 reserved + 1 family + 2 xport + 4 xaddr)
        ByteBuffer body = ByteBuffer.allocate(4 + 8);
        body.putShort(TurnTcpAllocator.ATTR_XOR_RELAYED_ADDRESS);
        body.putShort((short) 8);
        body.put((byte) 0);            // reserved
        body.put((byte) 0x01);         // family IPv4
        // XOR port = port ^ (cookie >> 16)
        int xPort = port ^ (TurnTcpAllocator.MAGIC_COOKIE >>> 16);
        body.putShort((short) (xPort & 0xFFFF));
        // XOR addr = addr ^ cookie
        int addr = ipToInt(ip);
        body.putInt(addr ^ TurnTcpAllocator.MAGIC_COOKIE);
        return body.array();
    }

    private static int ipToInt(String ip) {
        String[] parts = ip.split("\\.");
        return ((Integer.parseInt(parts[0]) & 0xFF) << 24)
             | ((Integer.parseInt(parts[1]) & 0xFF) << 16)
             | ((Integer.parseInt(parts[2]) & 0xFF) << 8)
             |  (Integer.parseInt(parts[3]) & 0xFF);
    }

    @Test
    void buildCreatePermissionRequest_includesXorPeerAddressAndIntegrity() {
        byte[] txn = new byte[12];
        TurnAuthMaterial auth = TurnAuthMaterial.of("u", "r", "n", new byte[16]);

        byte[] req = TurnTcpAllocator.buildCreatePermissionRequest(txn, "59.8.160.12", auth);

        ByteBuffer buf = ByteBuffer.wrap(req);
        assertThat(buf.getShort()).isEqualTo(TurnTcpAllocator.CREATE_PERMISSION_REQUEST);
        short bodyLen = buf.getShort();
        assertThat(buf.getInt()).isEqualTo(TurnTcpAllocator.MAGIC_COOKIE);
        // 마지막 24바이트 = MESSAGE-INTEGRITY TLV
        ByteBuffer last = ByteBuffer.wrap(req, req.length - 24, 24);
        assertThat(last.getShort()).isEqualTo(TurnTcpAllocator.ATTR_MESSAGE_INTEGRITY);

        // 첫 attribute = XOR-PEER-ADDRESS
        buf.position(20);
        assertThat(buf.getShort()).isEqualTo(TurnTcpAllocator.ATTR_XOR_PEER_ADDRESS);
        assertThat(buf.getShort()).isEqualTo((short) 8);
        assertThat(buf.get()).isZero();             // reserved
        assertThat(buf.get()).isEqualTo((byte) 0x01); // family IPv4
        buf.getShort();                              // xor port (port=0)
        int xorAddr = buf.getInt();
        // XOR 풀어서 비교 — 59.8.160.12 = 0x3B08A00C
        int expected = (59 << 24) | (8 << 16) | (160 << 8) | 12;
        assertThat(xorAddr ^ TurnTcpAllocator.MAGIC_COOKIE).isEqualTo(expected);

        assertThat(bodyLen).isGreaterThan((short) 12);
    }

    @Test
    void buildRefreshRequest_includesLifetimeAndIntegrity() {
        byte[] txn = new byte[12];
        TurnAuthMaterial auth = TurnAuthMaterial.of("u", "r", "n", new byte[16]);

        byte[] req = TurnTcpAllocator.buildRefreshRequest(txn, 600, auth);

        ByteBuffer buf = ByteBuffer.wrap(req);
        assertThat(buf.getShort()).isEqualTo(TurnTcpAllocator.REFRESH_REQUEST);
        buf.getShort(); // body len
        assertThat(buf.getInt()).isEqualTo(TurnTcpAllocator.MAGIC_COOKIE);
        // 마지막 24바이트 MESSAGE-INTEGRITY
        ByteBuffer last = ByteBuffer.wrap(req, req.length - 24, 24);
        assertThat(last.getShort()).isEqualTo(TurnTcpAllocator.ATTR_MESSAGE_INTEGRITY);

        // 첫 attribute = LIFETIME
        buf.position(20);
        assertThat(buf.getShort()).isEqualTo(TurnTcpAllocator.ATTR_LIFETIME);
        assertThat(buf.getShort()).isEqualTo((short) 4);
        assertThat(buf.getInt()).isEqualTo(600);
    }

    @Test
    void extractRelayAddress_xorReversesCorrectly() {
        // Build: header + XOR-RELAYED-ADDRESS only
        byte[] body = buildXorRelayedAddress("198.51.100.42", 12345);
        ByteBuffer msg = ByteBuffer.allocate(20 + body.length);
        msg.putShort(TurnTcpAllocator.ALLOCATE_SUCCESS);
        msg.putShort((short) body.length);
        msg.putInt(TurnTcpAllocator.MAGIC_COOKIE);
        for (int i = 0; i < 12; i++) msg.put((byte) i);
        msg.put(body);
        msg.position(20);

        String relay = TurnTcpAllocator.extractRelayAddress(msg, (short) body.length);
        assertThat(relay).isEqualTo("198.51.100.42:12345");
    }
}
