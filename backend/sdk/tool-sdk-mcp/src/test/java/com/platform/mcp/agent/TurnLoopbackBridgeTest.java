package com.platform.mcp.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-074: TurnLoopbackBridge 단위 테스트.
 *
 * <p>외부 클라이언트 ↔ TurnLoopbackBridge ↔ 가짜 Runner(ServerSocket) 흐름을 통해
 * 양방향 byte 전달과 EOF 전파를 검증한다.
 */
class TurnLoopbackBridgeTest {

    private ServerSocket fakeRunner;
    private ExecutorService runnerExecutor;
    private TurnLoopbackBridge bridge;

    @AfterEach
    void tearDown() throws IOException {
        if (bridge != null) bridge.shutdown();
        if (runnerExecutor != null) runnerExecutor.shutdownNow();
        if (fakeRunner != null && !fakeRunner.isClosed()) fakeRunner.close();
    }

    @Test
    void bridgesBidirectional() throws Exception {
        fakeRunner = new ServerSocket(0);
        int runnerPort = fakeRunner.getLocalPort();
        runnerExecutor = Executors.newSingleThreadExecutor();
        // Runner 가 클라이언트 데이터를 받고 echo+suffix 응답
        runnerExecutor.submit(() -> {
            try (Socket s = fakeRunner.accept()) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                OutputStream out = s.getOutputStream();
                byte[] buf = new byte[64];
                int n = in.read(buf);
                String received = new String(buf, 0, n, StandardCharsets.UTF_8);
                String reply = "ack:" + received;
                out.write(reply.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException ignore) {}
        });

        bridge = new TurnLoopbackBridge(runnerPort);

        // 외부 클라이언트 ↔ bridge — Pipe 대신 Socket 쌍으로 연결
        try (ServerSocket extServer = new ServerSocket(0);
             Socket clientSide = new Socket("127.0.0.1", extServer.getLocalPort())) {
            Socket serverSide = extServer.accept(); // bridge 가 받을 socket

            bridge.bridge(serverSide);

            // client → bridge → runner
            clientSide.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));
            clientSide.getOutputStream().flush();

            // runner → bridge → client
            byte[] buf = new byte[64];
            DataInputStream in = new DataInputStream(clientSide.getInputStream());
            waitUntil(() -> in.available() > 0, 3000);
            int n = in.read(buf);
            String reply = new String(buf, 0, n, StandardCharsets.UTF_8);
            assertThat(reply).isEqualTo("ack:hello");
        }
    }

    @Test
    void connectFailure_closesExternalSocket() throws IOException {
        // 사용 불가능한 포트(close 한 ServerSocket의 포트)
        ServerSocket tmp = new ServerSocket(0);
        int unusedPort = tmp.getLocalPort();
        tmp.close();

        bridge = new TurnLoopbackBridge(unusedPort);

        try (ServerSocket extServer = new ServerSocket(0);
             Socket clientSide = new Socket("127.0.0.1", extServer.getLocalPort())) {
            Socket serverSide = extServer.accept();
            assertThat(serverSide.isClosed()).isFalse();

            bridge.bridge(serverSide);

            waitUntil(serverSide::isClosed, 2000);
            assertThat(serverSide.isClosed()).isTrue();
        }
    }

    /** 조건이 참이 될 때까지 polling. timeout 시 그냥 반환 (검증은 호출 측 assertion). */
    private static void waitUntil(IoCondition cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (cond.test()) return;
            } catch (Exception ignore) {}
            try { Thread.sleep(20); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @FunctionalInterface
    private interface IoCondition {
        boolean test() throws Exception;
    }
}
