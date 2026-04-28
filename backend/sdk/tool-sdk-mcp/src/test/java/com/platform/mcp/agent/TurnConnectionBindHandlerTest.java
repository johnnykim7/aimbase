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
}
