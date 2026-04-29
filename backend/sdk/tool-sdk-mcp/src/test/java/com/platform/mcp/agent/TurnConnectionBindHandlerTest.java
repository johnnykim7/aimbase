package com.platform.mcp.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-074: TurnConnectionBindHandler 단위 테스트.
 *
 * <p>fake TURN 서버: control connection 위로 ConnectionAttempt indication 을 보내고,
 * 곧 새로 들어오는 ConnectionBind request 에 대해 success 응답한다.
 * Handler 가 socket 콜백을 호출하는지, 송신한 ConnectionBind 의 CONNECTION-ID 가 일치하는지 검증.
 */
class TurnConnectionBindHandlerTest {

    private ServerSocket fakeTurn;
    private ExecutorService executor;
    private TurnConnectionBindHandler handler;

    @AfterEach
    void tearDown() throws IOException {
        if (handler != null) handler.close();
        if (executor != null) executor.shutdownNow();
        if (fakeTurn != null && !fakeTurn.isClosed()) fakeTurn.close();
    }

    @Test
    void buildConnectionBindRequest_includesConnectionIdAndIntegrity() {
        byte[] txn = new byte[12];
        TurnAuthMaterial auth = TurnAuthMaterial.of("u", "r", "n", new byte[16]);
        byte[] req = TurnConnectionBindHandler.buildConnectionBindRequest(txn, 0xCAFEBABE, auth);

        ByteBuffer buf = ByteBuffer.wrap(req);
        assertThat(buf.getShort()).isEqualTo(TurnConnectionBindHandler.CONNECTION_BIND_REQUEST);
        short bodyLen = buf.getShort();
        assertThat(buf.getInt()).isEqualTo(TurnTcpAllocator.MAGIC_COOKIE);

        // 마지막 24바이트 = MESSAGE-INTEGRITY TLV
        ByteBuffer last = ByteBuffer.wrap(req, req.length - 24, 24);
        assertThat(last.getShort()).isEqualTo(TurnTcpAllocator.ATTR_MESSAGE_INTEGRITY);

        // CONNECTION-ID 가 첫 번째 attr
        buf.position(20);
        assertThat(buf.getShort()).isEqualTo(TurnConnectionBindHandler.ATTR_CONNECTION_ID);
        assertThat(buf.getShort()).isEqualTo((short) 4);
        assertThat(buf.getInt()).isEqualTo(0xCAFEBABE);
        assertThat(bodyLen).isGreaterThan((short) 8);
    }

    @Test
    void extractConnectionId_findsAttribute() {
        ByteBuffer msg = ByteBuffer.allocate(20 + 8);
        msg.putShort(TurnConnectionBindHandler.CONNECTION_ATTEMPT_INDICATION);
        msg.putShort((short) 8);
        msg.putInt(TurnTcpAllocator.MAGIC_COOKIE);
        for (int i = 0; i < 12; i++) msg.put((byte) i);
        msg.putShort(TurnConnectionBindHandler.ATTR_CONNECTION_ID);
        msg.putShort((short) 4);
        msg.putInt(0xDEADBEEF);
        msg.position(20);

        int id = TurnConnectionBindHandler.extractConnectionId(msg, (short) 8);
        assertThat(id).isEqualTo(0xDEADBEEF);
    }

    @Test
    void receiveLoop_handlesConnectionAttempt_callsSocketHandler() throws Exception {
        fakeTurn = new ServerSocket(0);
        int port = fakeTurn.getLocalPort();
        executor = Executors.newCachedThreadPool();

        AtomicReference<byte[]> capturedConnectionBindBody = new AtomicReference<>();
        CountDownLatch dataConnAccepted = new CountDownLatch(1);

        // (1) control connection 수락 → ConnectionAttempt indication 송신
        // (2) data connection 수락 → ConnectionBind 요청 수신 → success 응답
        executor.submit(() -> {
            try {
                Socket controlConn = fakeTurn.accept();
                // 즉시 ConnectionAttempt 송신
                int connectionId = 0xABCD1234;
                byte[] indication = buildConnectionAttempt(connectionId);
                controlConn.getOutputStream().write(indication);
                controlConn.getOutputStream().flush();

                // data connection 수락
                Socket dataConn = fakeTurn.accept();
                dataConnAccepted.countDown();

                DataInputStream in = new DataInputStream(dataConn.getInputStream());
                ByteBuffer req = TurnTcpAllocator.readStunMessage(in);
                capturedConnectionBindBody.set(req.array());

                // ConnectionBind success 응답 — txn 그대로 echo, body 빈
                req.position(8);
                byte[] txn = new byte[12];
                req.get(txn);
                ByteBuffer resp = ByteBuffer.allocate(20);
                resp.putShort(TurnConnectionBindHandler.CONNECTION_BIND_SUCCESS);
                resp.putShort((short) 0);
                resp.putInt(TurnTcpAllocator.MAGIC_COOKIE);
                resp.put(txn);
                dataConn.getOutputStream().write(resp.array());
                dataConn.getOutputStream().flush();

                // 데이터 채널 살린 채로 잠시 대기
                Thread.sleep(200);
                dataConn.close();
                controlConn.close();
            } catch (Exception e) {
                // 종료
            }
        });

        // 클라이언트 = control socket
        Socket controlClient = new Socket("127.0.0.1", port);

        AtomicReference<Socket> handedSocket = new AtomicReference<>();
        CountDownLatch handlerCalled = new CountDownLatch(1);

        handler = new TurnConnectionBindHandler(
                "127.0.0.1", port, controlClient,
                /* anonymous */ null,
                s -> { handedSocket.set(s); handlerCalled.countDown(); }
        );
        handler.start();

        // 검증
        assertThat(dataConnAccepted.await(3, TimeUnit.SECONDS)).as("data conn accepted").isTrue();
        assertThat(handlerCalled.await(3, TimeUnit.SECONDS)).as("socket handler called").isTrue();
        assertThat(handedSocket.get()).isNotNull();
        assertThat(handedSocket.get().isClosed()).isFalse();

        // ConnectionBind 요청에 CONNECTION-ID = 0xABCD1234 가 들어있는지
        byte[] body = capturedConnectionBindBody.get();
        assertThat(body).isNotNull();
        ByteBuffer parsed = ByteBuffer.wrap(body);
        assertThat(parsed.getShort()).isEqualTo(TurnConnectionBindHandler.CONNECTION_BIND_REQUEST);
        parsed.position(20);
        assertThat(parsed.getShort()).isEqualTo(TurnConnectionBindHandler.ATTR_CONNECTION_ID);
        assertThat(parsed.getShort()).isEqualTo((short) 4);
        assertThat(parsed.getInt()).isEqualTo(0xABCD1234);
    }

    private static byte[] buildConnectionAttempt(int connectionId) {
        ByteBuffer buf = ByteBuffer.allocate(20 + 8);
        buf.putShort(TurnConnectionBindHandler.CONNECTION_ATTEMPT_INDICATION);
        buf.putShort((short) 8);
        buf.putInt(TurnTcpAllocator.MAGIC_COOKIE);
        for (int i = 0; i < 12; i++) buf.put((byte) i);
        buf.putShort(TurnConnectionBindHandler.ATTR_CONNECTION_ID);
        buf.putShort((short) 4);
        buf.putInt(connectionId);
        return buf.array();
    }

    // ── CR-076: ERROR-CODE / NONCE 추출 + 438 재시도 ───────────────────

    @Test
    void extractErrorCode_parsesStaleNonce438() {
        // ERROR-CODE TLV: 4 byte value (2 reserved + 1 class + 1 number) + reason phrase
        // 438 = class 4, number 38
        byte[] reason = "Stale Nonce".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int valueLen = 4 + reason.length;
        int padding = (4 - (valueLen % 4)) % 4;
        int totalAttrSize = 4 + valueLen + padding;

        ByteBuffer msg = ByteBuffer.allocate(20 + totalAttrSize);
        msg.putShort((short) 0x011B);  // CONNECTION_BIND_ERROR
        msg.putShort((short) totalAttrSize);
        msg.putInt(TurnTcpAllocator.MAGIC_COOKIE);
        for (int i = 0; i < 12; i++) msg.put((byte) i);
        msg.putShort(TurnConnectionBindHandler.ATTR_ERROR_CODE);
        msg.putShort((short) valueLen);
        msg.putShort((short) 0);          // reserved
        msg.put((byte) 4);                // class
        msg.put((byte) 38);               // number → 438
        msg.put(reason);
        for (int i = 0; i < padding; i++) msg.put((byte) 0);
        msg.position(20);

        int code = TurnConnectionBindHandler.extractErrorCode(msg, (short) totalAttrSize);
        assertThat(code).isEqualTo(438);
    }

    @Test
    void extractNonce_findsAttribute() {
        String nonce = "fresh-nonce-xyz";
        byte[] nb = nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int padding = (4 - (nb.length % 4)) % 4;
        int totalAttrSize = 4 + nb.length + padding;

        ByteBuffer msg = ByteBuffer.allocate(20 + totalAttrSize);
        msg.putShort((short) 0x011B);
        msg.putShort((short) totalAttrSize);
        msg.putInt(TurnTcpAllocator.MAGIC_COOKIE);
        for (int i = 0; i < 12; i++) msg.put((byte) i);
        msg.putShort(TurnConnectionBindHandler.ATTR_NONCE);
        msg.putShort((short) nb.length);
        msg.put(nb);
        for (int i = 0; i < padding; i++) msg.put((byte) 0);
        msg.position(20);

        String extracted = TurnConnectionBindHandler.extractNonce(msg, (short) totalAttrSize);
        assertThat(extracted).isEqualTo(nonce);
    }

    @Test
    void connectionBind_438_triggersNonceRefreshAndRetry() throws Exception {
        fakeTurn = new ServerSocket(0);
        int port = fakeTurn.getLocalPort();
        executor = Executors.newCachedThreadPool();

        AtomicReference<byte[]> firstBindBody = new AtomicReference<>();
        AtomicReference<byte[]> secondBindBody = new AtomicReference<>();
        CountDownLatch secondBindReceived = new CountDownLatch(1);
        String freshNonce = "refreshed-nonce-001";

        // (1) control connection 수락 → ConnectionAttempt 송신
        // (2) 첫 data conn → ConnectionBind 받고 → 438 응답 (NONCE 포함)
        // (3) 두 번째 data conn → ConnectionBind 받고 → success 응답
        executor.submit(() -> {
            try {
                Socket controlConn = fakeTurn.accept();
                int connectionId = 0xCAFEBABE;
                controlConn.getOutputStream().write(buildConnectionAttempt(connectionId));
                controlConn.getOutputStream().flush();

                // 첫 번째 data connection
                Socket data1 = fakeTurn.accept();
                DataInputStream in1 = new DataInputStream(data1.getInputStream());
                ByteBuffer req1 = TurnTcpAllocator.readStunMessage(in1);
                firstBindBody.set(req1.array());

                req1.position(8);
                byte[] txn1 = new byte[12];
                req1.get(txn1);

                // ConnectionBind ERROR 응답: 438 + new NONCE
                byte[] errResp = buildConnectionBindError438(txn1, freshNonce);
                data1.getOutputStream().write(errResp);
                data1.getOutputStream().flush();
                data1.close();

                // 두 번째 data connection — 재시도
                Socket data2 = fakeTurn.accept();
                DataInputStream in2 = new DataInputStream(data2.getInputStream());
                ByteBuffer req2 = TurnTcpAllocator.readStunMessage(in2);
                secondBindBody.set(req2.array());
                secondBindReceived.countDown();

                req2.position(8);
                byte[] txn2 = new byte[12];
                req2.get(txn2);

                // success 응답
                ByteBuffer resp = ByteBuffer.allocate(20);
                resp.putShort(TurnConnectionBindHandler.CONNECTION_BIND_SUCCESS);
                resp.putShort((short) 0);
                resp.putInt(TurnTcpAllocator.MAGIC_COOKIE);
                resp.put(txn2);
                data2.getOutputStream().write(resp.array());
                data2.getOutputStream().flush();
                Thread.sleep(200);
                data2.close();
                controlConn.close();
            } catch (Exception ignore) {
                // 종료
            }
        });

        Socket controlClient = new Socket("127.0.0.1", port);

        // 인증 자료 보유한 session — 438 응답 후 nonce 가 갱신되어야 함
        TurnAuthSession session = TurnAuthSession.of("user", "realm", "old-nonce", new byte[16]);

        AtomicReference<Socket> handedSocket = new AtomicReference<>();
        CountDownLatch handlerCalled = new CountDownLatch(1);

        handler = new TurnConnectionBindHandler(
                "127.0.0.1", port, controlClient,
                session,
                s -> { handedSocket.set(s); handlerCalled.countDown(); }
        );
        handler.start();

        assertThat(secondBindReceived.await(5, TimeUnit.SECONDS)).as("retry sent").isTrue();
        assertThat(handlerCalled.await(5, TimeUnit.SECONDS)).as("success accepted").isTrue();
        assertThat(handedSocket.get()).isNotNull();

        // session 의 nonce 가 freshNonce 로 갱신됨
        String currentNonce = new String(session.current().nonceBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(currentNonce).isEqualTo(freshNonce);

        // 두 번째 ConnectionBind 요청에 fresh nonce 가 들어있는지 확인
        assertThat(secondBindBody.get()).isNotNull();
        // 메시지에 freshNonce 문자열이 포함됐는지 (NONCE attribute 안에 들어감)
        String secondReqAsString = new String(secondBindBody.get(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(secondReqAsString).contains(freshNonce);
    }

    private static byte[] buildConnectionBindError438(byte[] txn, String newNonce) {
        byte[] reason = "Stale Nonce".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] nb = newNonce.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        int errValueLen = 4 + reason.length;
        int errPad = (4 - (errValueLen % 4)) % 4;
        int errTlvSize = 4 + errValueLen + errPad;

        int noncePad = (4 - (nb.length % 4)) % 4;
        int nonceTlvSize = 4 + nb.length + noncePad;

        int totalAttrSize = errTlvSize + nonceTlvSize;
        ByteBuffer buf = ByteBuffer.allocate(20 + totalAttrSize);
        buf.putShort(TurnConnectionBindHandler.CONNECTION_BIND_ERROR);
        buf.putShort((short) totalAttrSize);
        buf.putInt(TurnTcpAllocator.MAGIC_COOKIE);
        buf.put(txn);

        // ERROR-CODE
        buf.putShort(TurnConnectionBindHandler.ATTR_ERROR_CODE);
        buf.putShort((short) errValueLen);
        buf.putShort((short) 0);
        buf.put((byte) 4);
        buf.put((byte) 38);
        buf.put(reason);
        for (int i = 0; i < errPad; i++) buf.put((byte) 0);

        // NONCE
        buf.putShort(TurnConnectionBindHandler.ATTR_NONCE);
        buf.putShort((short) nb.length);
        buf.put(nb);
        for (int i = 0; i < noncePad; i++) buf.put((byte) 0);

        return buf.array();
    }
}
