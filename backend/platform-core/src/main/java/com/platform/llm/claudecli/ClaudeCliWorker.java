package com.platform.llm.claudecli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.TokenUsage;
import com.platform.llm.model.UnifiedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * CR-050 Phase 1 (PRD-306).
 * 단일 Claude CLI 프로세스 래퍼.
 *
 * 수명:
 *   new ClaudeCliWorker(...) → start() → turnFirst(...) → turn(...) * N → close()
 *
 * 중요:
 *   - stderr drain 스레드 필수 (버퍼 차면 프로세스 hang).
 *   - stdout도 별도 스레드에서 NDJSON 라인 단위로 읽어 {@code eventQueue}에 적재.
 *   - 동일 워커에 대한 {@code turn} 호출은 {@link ReentrantLock}으로 직렬화된다.
 *   - {@code --tools ""} 로 도구 전면 봉인 — 순수 LLM_CALL 경로만 지원.
 */
public class ClaudeCliWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliWorker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** stdout 파싱 스레드가 라인별로 적재하는 이벤트 큐. consumer 측은 {@code type=result} 이벤트까지 polling. */
    private final BlockingQueue<StreamEvent> eventQueue = new LinkedBlockingQueue<>();

    private final String binaryPath;
    private final String model;            // nullable → CLI 기본값 사용
    private final String resumeSessionId;  // nullable → 신규 세션
    private final boolean forkSession;
    private final String configDir;        // CLAUDE_CONFIG_DIR, nullable
    private final Duration turnTimeout;

    private final ReentrantLock turnLock = new ReentrantLock();

    private Process process;
    private BufferedWriter stdin;
    private Thread stdoutThread;
    private Thread stderrThread;

    private volatile String sessionId;       // CLI `system/init` 이벤트에서 추출
    private volatile boolean firstTurnSent;

    public ClaudeCliWorker(String binaryPath, String model, String resumeSessionId,
                           boolean forkSession, String configDir, Duration turnTimeout) {
        this.binaryPath = binaryPath;
        this.model = model;
        this.resumeSessionId = resumeSessionId;
        this.forkSession = forkSession;
        this.configDir = configDir;
        this.turnTimeout = turnTimeout != null ? turnTimeout : Duration.ofSeconds(300);
    }

    /** 프로세스 기동 + 파서/드레인 스레드 시작. 반환 후 turn*() 호출 가능 상태. */
    public synchronized void start() throws IOException {
        if (process != null) {
            throw new IllegalStateException("Worker already started");
        }
        List<String> cmd = buildCommand();
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        if (configDir != null && !configDir.isBlank()) {
            pb.environment().put("CLAUDE_CONFIG_DIR", configDir);
        }
        pb.environment().remove("CLAUDECODE");   // 중첩 세션 방지

        log.info("ClaudeCliWorker start: cmd={}, configDir={}", cmd, configDir);
        this.process = pb.start();
        this.stdin = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));

        this.stdoutThread = Thread.ofVirtual()
                .name("cli-worker-stdout-" + process.pid())
                .start(this::drainStdout);
        this.stderrThread = Thread.ofVirtual()
                .name("cli-worker-stderr-" + process.pid())
                .start(this::drainStderr);
    }

    /** 첫 턴: 전체 messages 주입 (CLI 맥락 초기화). */
    public LLMResponse turnFirst(List<UnifiedMessage> messages) {
        return turnInternal(messages, true);
    }

    /** 이후 턴: 마지막 user 메시지만 주입 (CLI가 이전 맥락 유지). */
    public LLMResponse turn(UnifiedMessage lastUser) {
        return turnInternal(List.of(lastUser), false);
    }

    /**
     * 스트리밍 턴. {@code eventConsumer} 는 stdout의 assistant 이벤트 텍스트 델타를 수신한다.
     * 최종 LLMResponse는 result 이벤트 수신 후 반환.
     */
    public LLMResponse turnStream(List<UnifiedMessage> messages, boolean first,
                                   Consumer<String> deltaConsumer) {
        return turnInternal0(messages, first, deltaConsumer);
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    @Override
    public void close() {
        if (process == null) return;
        try {
            if (stdin != null) {
                try { stdin.close(); } catch (IOException ignored) {}
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            if (stdoutThread != null) stdoutThread.interrupt();
            if (stderrThread != null) stderrThread.interrupt();
            log.info("ClaudeCliWorker closed (pid={})", process.pid());
        }
    }

    // ─── 내부 ────────────────────────────────────────────────────────────

    private LLMResponse turnInternal(List<UnifiedMessage> messages, boolean first) {
        return turnInternal0(messages, first, null);
    }

    private LLMResponse turnInternal0(List<UnifiedMessage> messages, boolean first,
                                       Consumer<String> deltaConsumer) {
        if (process == null || !process.isAlive()) {
            throw new ClaudeCliException("Worker not running");
        }
        turnLock.lock();
        long start = System.currentTimeMillis();
        try {
            if (first && firstTurnSent) {
                throw new IllegalStateException("turnFirst already called on this worker");
            }
            if (!first && !firstTurnSent) {
                throw new IllegalStateException("turn() called before turnFirst()");
            }

            eventQueue.clear();
            writeUserMessages(messages);
            if (first) firstTurnSent = true;

            return awaitResult(start, deltaConsumer);
        } catch (IOException e) {
            throw new ClaudeCliException("stdin write failed: " + e.getMessage(), e);
        } finally {
            turnLock.unlock();
        }
    }

    private void writeUserMessages(List<UnifiedMessage> messages) throws IOException {
        for (UnifiedMessage msg : messages) {
            if (msg.role() != UnifiedMessage.Role.USER) {
                // CLI stream-json은 assistant/system 메시지 재주입을 허용하지 않는다.
                // system 은 첫 턴에 --system-prompt 인자로 넣는 대안이 있으나 현재는 user 만.
                log.debug("Skipping non-USER message in CLI input (role={})", msg.role());
                continue;
            }
            String text = flattenText(msg);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "user");
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "user");
            message.put("content", text);
            event.put("message", message);
            String line = MAPPER.writeValueAsString(event);
            stdin.write(line);
            stdin.newLine();
        }
        stdin.flush();
    }

    private String flattenText(UnifiedMessage msg) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : msg.content()) {
            if (block instanceof ContentBlock.Text t) sb.append(t.text());
        }
        return sb.toString();
    }

    private LLMResponse awaitResult(long startMs, Consumer<String> deltaConsumer) {
        long deadline = startMs + turnTimeout.toMillis();
        StringBuilder assistantBuffer = new StringBuilder();
        TokenUsage usage = new TokenUsage(0, 0);
        double costUsd = 0.0;
        String id = UUID.randomUUID().toString();
        String modelReport = model != null ? model : "anthropic-cli";

        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new ClaudeCliTimeoutException(
                        "turn timeout after " + turnTimeout.toSeconds() + "s");
            }
            StreamEvent ev;
            try {
                ev = eventQueue.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ClaudeCliException("interrupted while awaiting result");
            }
            if (ev == null) {
                if (!process.isAlive()) {
                    throw new ClaudeCliException(
                            "CLI process exited during turn (code=" + process.exitValue() + ")");
                }
                continue;
            }
            if (ev.kind == StreamEventKind.ERROR) {
                throw new ClaudeCliException("stdout parser error: " + ev.rawLine);
            }

            Map<String, Object> json = ev.parsed;
            String type = json != null ? (String) json.get("type") : null;
            if (type == null) continue;

            switch (type) {
                case "system" -> {
                    Object sid = json.get("session_id");
                    if (sid != null && sessionId == null) {
                        sessionId = sid.toString();
                        log.debug("CLI session_id captured: {}", sessionId);
                    }
                }
                case "assistant" -> {
                    String delta = extractAssistantText(json);
                    if (delta != null && !delta.isEmpty()) {
                        assistantBuffer.append(delta);
                        if (deltaConsumer != null) deltaConsumer.accept(delta);
                    }
                }
                case "result" -> {
                    Object sid = json.get("session_id");
                    if (sid != null && sessionId == null) sessionId = sid.toString();
                    Object cost = json.get("total_cost_usd");
                    if (cost instanceof Number n) costUsd = n.doubleValue();
                    usage = parseUsage(json);
                    String finalText = assistantBuffer.toString();
                    Object resultField = json.get("result");
                    if ((finalText == null || finalText.isEmpty()) && resultField instanceof String rs) {
                        finalText = rs;
                    }
                    long latency = System.currentTimeMillis() - startMs;
                    return new LLMResponse(
                            id,
                            modelReport,
                            List.of(new ContentBlock.Text(finalText)),
                            List.of(),
                            usage,
                            LLMResponse.FinishReason.END,
                            latency,
                            costUsd
                    );
                }
                case "rate_limit_event" -> log.warn("CLI rate_limit_event: {}", json);
                default -> log.trace("CLI event ignored: type={}", type);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String extractAssistantText(Map<String, Object> event) {
        Object message = event.get("message");
        if (!(message instanceof Map<?, ?> msg)) return null;
        Object content = msg.get("content");
        if (!(content instanceof List<?> list)) return null;
        StringBuilder sb = new StringBuilder();
        for (Object b : list) {
            if (b instanceof Map<?, ?> block && "text".equals(block.get("type"))) {
                Object t = block.get("text");
                if (t != null) sb.append(t);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private TokenUsage parseUsage(Map<String, Object> resultEvent) {
        // 결정 2: stream-json에서 우선 파싱, 실패 시 0 폴백.
        // CLI result 이벤트는 보통 { "usage": { "input_tokens": N, "output_tokens": N,
        //   "cache_creation_input_tokens": N, "cache_read_input_tokens": N } } 를 포함.
        Object u = resultEvent.get("usage");
        if (!(u instanceof Map<?, ?> usage)) {
            log.debug("CLI result event has no 'usage' — reporting 0 tokens (usage_unavailable=true)");
            return new TokenUsage(0, 0);
        }
        int in = asInt(usage.get("input_tokens"));
        int out = asInt(usage.get("output_tokens"));
        int cwrite = asInt(usage.get("cache_creation_input_tokens"));
        int cread = asInt(usage.get("cache_read_input_tokens"));
        return new TokenUsage(in, out, cwrite, cread, 0, 0);
    }

    private static int asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return 0;
    }

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(binaryPath != null ? binaryPath : "claude");
        cmd.add("-p");
        cmd.add("--verbose");
        cmd.add("--input-format");
        cmd.add("stream-json");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--tools");
        cmd.add("");
        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }
        if (resumeSessionId != null && !resumeSessionId.isBlank()) {
            cmd.add("--resume");
            cmd.add(resumeSessionId);
            if (forkSession) cmd.add("--fork-session");
        }
        return cmd;
    }

    private void drainStdout() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    Map<String, Object> parsed = MAPPER.readValue(trimmed, MAP_TYPE);
                    eventQueue.offer(new StreamEvent(StreamEventKind.EVENT, parsed, trimmed));
                } catch (Exception e) {
                    log.trace("CLI stdout parse skip: {}", trimmed);
                }
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                log.warn("CLI stdout drain error: {}", e.getMessage());
            }
        }
    }

    private void drainStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("CLI stderr: {}", line);
            }
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                log.warn("CLI stderr drain error: {}", e.getMessage());
            }
        }
    }

    // ─── 내부 모델 ───────────────────────────────────────────────────────

    enum StreamEventKind { EVENT, ERROR }

    record StreamEvent(StreamEventKind kind, Map<String, Object> parsed, String rawLine) {}
}
