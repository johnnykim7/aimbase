package com.platform.llm.claudecli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.TokenUsage;
import com.platform.llm.model.ToolCall;
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
    /** Phase 9: --mcp-config 로 CLI 에 주입할 JSON. 비어있으면 {"mcpServers":{}} 로 격리. */
    private final String mcpConfigJson;
    /**
     * CR-068 후속: --system-prompt flag 로 CLI 의 기본 system prompt 를 우리 prompt 로 교체.
     * null 이면 CLI 기본(Claude Code 학습 패턴) 사용. 비어있으면 SYSTEM 메시지 prepend 폴백.
     * start() 호출 전에만 setSystemPrompt 로 변경 가능.
     */
    private volatile String systemPromptOverride;

    private final ReentrantLock turnLock = new ReentrantLock();

    private Process process;
    private BufferedWriter stdin;
    private Thread stdoutThread;
    private Thread stderrThread;

    private volatile String sessionId;       // CLI `system/init` 이벤트에서 추출
    private volatile boolean firstTurnSent;

    /** 기본 생성자 — 도구 비연결(빈 MCP 설정) 모드. 기존 호출부 호환. */
    public ClaudeCliWorker(String binaryPath, String model, String resumeSessionId,
                           boolean forkSession, String configDir, Duration turnTimeout) {
        this(binaryPath, model, resumeSessionId, forkSession, configDir, turnTimeout, null);
    }

    public ClaudeCliWorker(String binaryPath, String model, String resumeSessionId,
                           boolean forkSession, String configDir, Duration turnTimeout,
                           String mcpConfigJson) {
        this.binaryPath = binaryPath;
        this.model = model;
        this.resumeSessionId = resumeSessionId;
        this.forkSession = forkSession;
        this.configDir = configDir;
        this.turnTimeout = turnTimeout != null ? turnTimeout : Duration.ofSeconds(300);
        this.mcpConfigJson = (mcpConfigJson == null || mcpConfigJson.isBlank())
                ? "{\"mcpServers\":{}}"
                : mcpConfigJson;
    }

    /**
     * CR-068: start() 호출 전 SYSTEM 메시지를 --system-prompt flag 로 주입할 텍스트 설정.
     * null/빈 문자열 → CLI 기본 system prompt (Claude Code 학습 패턴) 사용.
     * 설정 시 — CLI 기본 prompt 대체 + user 메시지 SYSTEM prepend 도 생략 (이중 노출 방지).
     */
    public void setSystemPromptOverride(String systemPrompt) {
        if (process != null) {
            throw new IllegalStateException("Worker already started; cannot set systemPromptOverride after start()");
        }
        this.systemPromptOverride = (systemPrompt == null || systemPrompt.isBlank()) ? null : systemPrompt;
    }

    /** SYSTEM prepend 폴백 여부 — override 가 설정됐으면 false. */
    boolean hasSystemPromptOverride() {
        return systemPromptOverride != null;
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
        // CR-068 후속: systemPromptOverride 가 설정됐으면 SYSTEM 메시지는 이미 --system-prompt flag 로
        // CLI 에 주입됨 → user prepend 생략 (이중 노출 방지). 미설정 시 기존 prepend 폴백 유지.
        StringBuilder systemPrefix = new StringBuilder();
        if (systemPromptOverride == null) {
            // CR-050: SYSTEM 메시지는 첫 user 메시지 앞에 prepend (CLI stream-json 은 system role 재주입 미지원).
            for (UnifiedMessage msg : messages) {
                if (msg.role() == UnifiedMessage.Role.SYSTEM) {
                    if (systemPrefix.length() > 0) systemPrefix.append("\n\n");
                    systemPrefix.append(flattenText(msg));
                }
            }
        }

        boolean firstUserConsumed = false;
        for (UnifiedMessage msg : messages) {
            switch (msg.role()) {
                case SYSTEM -> {
                    // override 있으면 무시, 없으면 위에서 systemPrefix 로 누적됨.
                }
                case USER -> {
                    String text = flattenText(msg);
                    if (!firstUserConsumed && systemPrefix.length() > 0) {
                        text = systemPrefix + "\n\n" + text;
                        firstUserConsumed = true;
                    }
                    writeUserLine(text);
                }
                case TOOL_RESULT -> {
                    // CR-050: OrchestratorEngine 이 도구 실행 결과를 다시 주입할 때.
                    // stream-json: {type:"user", message:{role:"user", content:[{type:"tool_result", tool_use_id, content}]}}
                    writeToolResultLine(msg);
                }
                default -> log.debug("Skipping message role={} in CLI input", msg.role());
            }
        }
        // 입력에 USER 가 없고 SYSTEM 만 있던 경우(이론적, override 없을 때만), system 만 user 라인으로 보낸다.
        if (!firstUserConsumed && systemPrefix.length() > 0) {
            writeUserLine(systemPrefix.toString());
        }
        stdin.flush();
    }

    private void writeUserLine(String text) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "user");
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", text);
        event.put("message", message);
        String line = MAPPER.writeValueAsString(event);
        log.info("[CLI-STDIN] writing user line: textLen={}, jsonLen={}, preview='{}'",
                text != null ? text.length() : 0,
                line.length(),
                text != null && text.length() > 200 ? text.substring(0, 200) + "..." : text);
        stdin.write(line);
        stdin.newLine();
    }

    private void writeToolResultLine(UnifiedMessage msg) throws IOException {
        java.util.List<Map<String, Object>> blocks = new java.util.ArrayList<>();
        for (ContentBlock b : msg.content()) {
            if (b instanceof ContentBlock.ToolResult tr) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "tool_result");
                block.put("tool_use_id", tr.toolUseId());
                block.put("content", tr.content());
                blocks.add(block);
            }
        }
        if (blocks.isEmpty()) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "user");
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", blocks);
        event.put("message", message);
        String line = MAPPER.writeValueAsString(event);
        stdin.write(line);
        stdin.newLine();
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
                    if ("init".equals(json.get("subtype"))) {
                        log.info("[CLI-INIT] tools={}, mcp_servers={}",
                                json.get("tools"), json.get("mcp_servers"));
                    }
                }
                case "assistant" -> {
                    String delta = extractAssistantText(json);
                    if (delta != null && !delta.isEmpty()) {
                        assistantBuffer.append(delta);
                        if (deltaConsumer != null) deltaConsumer.accept(delta);
                    }
                    // CR-050 Phase 9: CLI 의 tool_use/tool_result 흐름은 CLI 내부에서 완결된다 (실측됨).
                    // 외부(우리 어댑터)는 관찰만 하고 OrchestratorEngine 도구 루프에 포함시키지 않는다 —
                    // 포함시키면 OrchestratorEngine 이 다음 턴 tool_result 를 주입하려 하지만 CLI 가
                    // 이미 자기 안에서 결과를 받아 다음 응답 생성 중이라 충돌한다.
                    java.util.List<ToolCall> tu = extractToolUses(json);
                    if (!tu.isEmpty()) {
                        log.info("[CLI-OBS] CLI invoked {} tool(s) internally: {}",
                                tu.size(),
                                tu.stream().map(ToolCall::name).toList());
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

                    // CR-050 Phase 9: CLI 가 도구 루프를 자체적으로 완결하므로 외부에는 항상
                    // 최종 텍스트 + finishReason=END 만 노출. tool_use 는 관찰 로그용으로만 사용.
                    java.util.List<ContentBlock> content = new ArrayList<>();
                    content.add(new ContentBlock.Text(finalText != null ? finalText : ""));

                    return new LLMResponse(
                            id,
                            modelReport,
                            content,
                            java.util.List.of(),
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

    /**
     * assistant 이벤트의 content[] 에서 type=tool_use 블록만 추출해 ToolCall 로 변환.
     * stream-json 포맷: {type:"tool_use", id:"toolu_...", name:"...", input:{...}}
     *
     * <p>Phase 9: MCP 채널로 노출된 도구는 CLI 가 {@code mcp__<server>__<tool>} 형식으로 발행한다.
     * OrchestratorEngine 의 ToolRegistry 는 원본 도구명({@code <tool>}) 으로 등록되어 있으므로
     * prefix 를 제거해 매핑한다.
     */
    @SuppressWarnings("unchecked")
    private java.util.List<ToolCall> extractToolUses(Map<String, Object> event) {
        Object message = event.get("message");
        if (!(message instanceof Map<?, ?> msg)) return java.util.List.of();
        Object content = msg.get("content");
        if (!(content instanceof List<?> list)) return java.util.List.of();
        java.util.List<ToolCall> out = new ArrayList<>();
        for (Object b : list) {
            if (!(b instanceof Map<?, ?> block)) continue;
            if (!"tool_use".equals(block.get("type"))) continue;
            String tid = block.get("id") != null ? block.get("id").toString() : null;
            String rawName = block.get("name") != null ? block.get("name").toString() : null;
            String name = stripMcpPrefix(rawName);
            Object input = block.get("input");
            Map<String, Object> inputMap = input instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : Map.of();
            out.add(new ToolCall(tid, name, inputMap));
        }
        return out;
    }

    /**
     * CLI 가 발행하는 MCP 도구 이름 {@code mcp__<server>__<tool>} 에서 server prefix 를 제거.
     * 예: {@code mcp__aimbase__bash} → {@code bash}. 비-MCP 도구 이름은 그대로 반환.
     */
    public static String stripMcpPrefix(String name) {
        if (name == null || !name.startsWith("mcp__")) return name;
        int second = name.indexOf("__", 5);
        if (second < 0) return name;
        return name.substring(second + 2);
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
        // CR-050 Phase 9: 도구는 MCP 채널로만 노출.
        // --tools "" : CLI 내장 빌트인 도구 봉인
        // --strict-mcp-config : 사용자 글로벌 ~/.claude 설정 무시 (오염 방지)
        // --mcp-config <json> : Aimbase 가 명시한 MCP 서버만 연결.
        //   - mcpConfigJson="{\"mcpServers\":{}}" 이면 도구 비연결(순수 텍스트 LLM_CALL)
        //   - aimbase-agent 가 노출된 설정이면 BashTool/ReadTool 등이 native tool_use 로 연결됨.
        cmd.add("--tools");
        cmd.add("");
        cmd.add("--strict-mcp-config");
        cmd.add("--mcp-config");
        cmd.add(mcpConfigJson);
        // CR-050 Phase 9: CLI 의 자체 permission prompt 는 stdin/stdout 자동화 환경에서
        // 처리 불가 → 모든 tool_use 가 "차단" 으로 응답되어 모델이 무한 재시도. MCP 서버
        // (aimbase-agent) 자체가 도구 권한·감사를 보유하므로 CLI 권한 게이트는 봉인.
        cmd.add("--permission-mode");
        cmd.add("bypassPermissions");
        // CR-068 후속: --append-system-prompt 로 CLI 기본 system prompt 끝에 우리 prompt 추가.
        // (--system-prompt 는 cwd/env 같은 dynamic 섹션도 같이 제거되어 CLI 가 워크스페이스 인식 못 함)
        // append 방식: CLI 의 environment(cwd, env, git status 등) + Claude Code 기본 톤은 유지하되,
        // 우리(Aimbase) 의 도구 사용 가이드·anti-hallucination 지시문·역할 정의를 추가 통제.
        if (systemPromptOverride != null) {
            cmd.add("--append-system-prompt");
            cmd.add(systemPromptOverride);
        }
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
