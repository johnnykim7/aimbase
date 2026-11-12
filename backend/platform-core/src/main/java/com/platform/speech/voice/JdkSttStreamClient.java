package com.platform.speech.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * CR-186: JDK 웹소켓으로 받아쓰기 서버에 잇는다. JDK 웹소켓은 앞 보내기가 끝나기 전에 다음을 보내면 예외를 던지므로
 * 보내기를 한 줄로 이어 붙인다.
 */
@Component
public class JdkSttStreamClient implements SttStreamClient {

    private static final Logger log = LoggerFactory.getLogger(JdkSttStreamClient.class);
    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    public CompletableFuture<Stream> open(String baseUrl, String apiKey, String sessionId, Duration connectTimeout,
                                          Listener listener) {
        URI uri = URI.create(SttStreamClient.streamUrl(baseUrl, apiKey, sessionId));
        return http.newWebSocketBuilder()
                .connectTimeout(connectTimeout)
                .buildAsync(uri, new Bridge(listener))
                .thenApply(JdkStream::new);
    }

    private static final class JdkStream implements Stream {
        private final WebSocket ws;
        private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);

        JdkStream(WebSocket ws) {
            this.ws = ws;
        }

        @Override
        public void sendAudio(byte[] chunk) {
            if (chunk == null || chunk.length == 0) return;
            ByteBuffer copy = ByteBuffer.wrap(chunk.clone());
            enqueue(() -> ws.sendBinary(copy, true));
        }

        @Override
        public void sendEnd() {
            enqueue(() -> ws.sendBinary(ByteBuffer.allocate(0), true));
        }

        @Override
        public void close() {
            enqueue(() -> ws.sendClose(WebSocket.NORMAL_CLOSURE, ""))
                    .whenComplete((v, e) -> ws.abort());
        }

        private synchronized CompletableFuture<?> enqueue(java.util.function.Supplier<CompletableFuture<WebSocket>> send) {
            tail = tail.handle((v, e) -> null)
                    .thenCompose(v -> ws.isOutputClosed() ? CompletableFuture.completedFuture(ws) : send.get())
                    .exceptionally(e -> {
                        log.debug("[CR-186] 받아쓰기 서버로 보내지 못했다: {}", e.getMessage());
                        return null;
                    });
            return tail;
        }
    }

    private static final class Bridge implements WebSocket.Listener {
        private final Listener listener;
        private final StringBuilder text = new StringBuilder();

        Bridge(Listener listener) {
            this.listener = listener;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String msg = text.toString();
                text.setLength(0);
                listener.onText(msg);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            listener.onClosed(statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            listener.onError(error);
        }
    }
}
