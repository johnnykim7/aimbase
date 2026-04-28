package com.platform.mcp.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CR-074: TURN ConnectionBind 로 받은 Socket 을 agent 의 내장 Tomcat 으로 연결하는 byte pump.
 *
 * <p>흐름:
 * <ol>
 *   <li>{@link TurnConnectionBindHandler} 가 ConnectionBind 까지 끝낸 외부 Socket 을 콜백으로 전달</li>
 *   <li>{@link #bridge(Socket)} 가 같은 호스트의 {@code localhost:runnerPort} (Tomcat) 로 새 TCP 연결</li>
 *   <li>두 socket 을 양방향 byte pump (스레드 2개)</li>
 *   <li>한쪽이 EOF/에러 → 양쪽 모두 close</li>
 * </ol>
 *
 * <p>Tomcat 의 RunnerController 가 평소처럼 인증/SSE/예외처리 모두 담당하므로
 * 본 클래스는 코드 중복 없이 raw byte 만 흘려보낸다.
 */
public class TurnLoopbackBridge {

    private static final Logger log = LoggerFactory.getLogger(TurnLoopbackBridge.class);

    private static final int BUFFER_SIZE = 16 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 3000;

    private final String runnerHost;
    private final int runnerPort;
    private final ExecutorService pumpExecutor;

    public TurnLoopbackBridge(int runnerPort) {
        this("127.0.0.1", runnerPort);
    }

    public TurnLoopbackBridge(String runnerHost, int runnerPort) {
        this.runnerHost = runnerHost;
        this.runnerPort = runnerPort;
        this.pumpExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "turn-bridge-pump");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 외부 Socket 을 받아 loopback 에 bridge.
     * 호출 즉시 반환 — 두 byte pump 는 별도 스레드에서 실행된다.
     */
    public void bridge(Socket external) {
        Socket loopback = null;
        try {
            loopback = new Socket();
            loopback.connect(new InetSocketAddress(runnerHost, runnerPort), CONNECT_TIMEOUT_MS);
            loopback.setTcpNoDelay(true);
            loopback.setKeepAlive(true);

            final Socket extFinal = external;
            final Socket lbFinal = loopback;

            pumpExecutor.submit(() -> pump(extFinal, lbFinal, "ext→lb"));
            pumpExecutor.submit(() -> pump(lbFinal, extFinal, "lb→ext"));
            log.debug("TURN loopback bridge established → {}:{}", runnerHost, runnerPort);
        } catch (IOException e) {
            log.warn("TURN loopback bridge connect failed ({}:{}): {}", runnerHost, runnerPort, e.getMessage());
            closeQuietly(external);
            closeQuietly(loopback);
        }
    }

    public void shutdown() {
        pumpExecutor.shutdownNow();
    }

    /**
     * src → dst 단방향 byte pump. EOF / IOException 시 양쪽 close.
     */
    static void pump(Socket src, Socket dst, String label) {
        byte[] buf = new byte[BUFFER_SIZE];
        try (InputStream in = src.getInputStream(); OutputStream out = dst.getOutputStream()) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                out.write(buf, 0, n);
                out.flush();
            }
            // EOF
            try { dst.shutdownOutput(); } catch (IOException ignore) {}
        } catch (IOException e) {
            log.debug("TURN bridge pump {} terminated: {}", label, e.getMessage());
        } finally {
            closeQuietly(src);
            closeQuietly(dst);
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignore) {}
    }
}
