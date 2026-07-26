package com.platform.runner.claudecli;

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
    /** CR-069: 도구 노출 모드 (AIMBASE/NATIVE/HYBRID). null/미설정 시 빌더 default(AIMBASE). */
    private volatile ClaudeCliCommandBuilder.ToolMode toolMode;
    /**
     * CR-126: AIMBASE 모드에서 봉인할 CLI built-in 도구 목록 override.
     * null/빈 목록이면 {@link ClaudeCliCommandBuilder#DEFAULT_SEALED_NATIVE_TOOLS} 폴백.
     * CLI 버전업으로 built-in 이 늘었을 때 재배포 없이 대응하기 위한 설정 경로.
     */
    private volatile List<String> sealedNativeTools;
    /**
     * CR-104: 이 워커가 CLI 에 허용할 도구(원본 도구명) 목록. null/빈 목록이면 미적용
     * (서버 MCP endpoint 가 노출하는 전체를 그대로 사용). 설정 시 {@code mcp__aimbase-server__<tool>}
     * 형식으로 변환해 CLI {@code --allowedTools} 로 주입 → CLI 가 가져가는 도구 = API tools 목록.
     * start() 호출 전에만 setAllowedTools 로 변경 가능.
     */
    private volatile List<String> allowedToolNames;
    /**
     * CR-117: CLI 본체 {@code Agent} 서브에이전트 차단 여부. true 면 buildCommand 가
     * {@code --disallowedTools Agent} 를 주입해 자율 서브에이전트 spawn 을 막는다.
     * 기본 false(=현행: subagent 허용). connection.config.subagent_enabled=false 일 때만 true.
     */
    private volatile boolean disallowSubagent;
    /**
     * CR-107 후속: CLI 프로세스 cwd(working directory). 워크플로우 run 격리 workspace 절대경로.
     * 설정 시 start() 가 {@code pb.directory()} 로 적용 → HYBRID 모드의 CLI 내장 Read/Bash 가
     * 상대경로(attachments/...)로도 작업장을 읽는다. null 이면 미설정(기존 동작 = 프로세스 기본 cwd).
     * start() 호출 전에만 setWorkingDirectory 로 변경 가능.
     */
    private volatile String workingDirectory;
    /** CR-104: --allowedTools 에 붙일 MCP server prefix. resolveMcpConfigJson 의 server 키와 일치해야 함. */
    private static final String MCP_SERVER_PREFIX = "mcp__aimbase-server__";
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

    // CR-121: 좀비 reaper 가 idle 워커를 판별하기 위한 활동 타임스탬프.
    private final long createdAtMs = System.currentTimeMillis();
    private volatile long lastActivityMs = System.currentTimeMillis();

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

    /**
     * CR-069: start() 호출 전 도구 노출 모드 설정.
     * null 이면 빌더 default(AIMBASE) 적용.
     */
    public void setToolMode(ClaudeCliCommandBuilder.ToolMode mode) {
        if (process != null) {
            throw new IllegalStateException("Worker already started; cannot set toolMode after start()");
        }
        this.toolMode = mode;
    }

    /**
     * CR-126: start() 호출 전, AIMBASE 봉인 대상 built-in 목록 override.
     * null/빈 목록이면 빌더 기본 상수 사용.
     */
    public void setSealedNativeTools(List<String> toolNames) {
        if (process != null) {
            throw new IllegalStateException("Worker already started; cannot set sealedNativeTools after start()");
        }
        this.sealedNativeTools = (toolNames == null || toolNames.isEmpty()) ? null : List.copyOf(toolNames);
    }

    /**
     * CR-104: start() 호출 전, 이 워커가 CLI 에 허용할 도구(원본 도구명) 목록 설정.
     * null/빈 목록이면 미적용. 같은 runId 워커가 이미 살아있으면 호출처에서 무시(Pool 정책).
     */
    public void setAllowedTools(List<String> toolNames) {
        if (process != null) {
            throw new IllegalStateException("Worker already started; cannot set allowedTools after start()");
        }
        this.allowedToolNames = (toolNames == null || toolNames.isEmpty()) ? null : List.copyOf(toolNames);
    }

    /**
     * CR-117: start() 호출 전, CLI 본체 {@code Agent} 서브에이전트 차단 여부 설정.
     * true 면 buildCommand 가 {@code --disallowedTools Agent} 를 주입한다. 같은 runId 워커가
     * 이미 살아있으면 호출처에서 무시(Pool 정책 — run 단위 도구 집합 고정).
     */
    public void setDisallowSubagent(boolean disallow) {
        if (process != null) {
            throw new IllegalStateException("Worker already started; cannot set disallowSubagent after start()");
        }
        this.disallowSubagent = disallow;
    }

    /**
     * CR-107 후속: start() 호출 전, CLI 프로세스 cwd(작업장 절대경로) 설정.
     * null/빈 값이면 미적용(프로세스 기본 cwd 유지).
     */
    public void setWorkingDirectory(String dir) {
        if (process != null) {
            throw new IllegalStateException("Worker already started; cannot set workingDirectory after start()");
        }
        this.workingDirectory = (dir == null || dir.isBlank()) ? null : dir;
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

        // CR-107 후속: CLI cwd 를 작업장으로 설정 → HYBRID CLI 내장 Read/Bash 가 상대경로로도 작업장을 읽는다.
        // 존재하는 디렉토리일 때만 적용(없는 경로면 ProcessBuilder.start 가 IOException → 안전하게 무시).
        if (workingDirectory != null) {
            java.io.File wd = new java.io.File(workingDirectory);
            if (wd.isDirectory()) {
                pb.directory(wd);
            } else {
                log.warn("ClaudeCliWorker: workingDirectory '{}' is not a directory — using default cwd", workingDirectory);
            }
        }

        log.info("ClaudeCliWorker start: cmd={}, configDir={}, cwd={}", cmd, configDir,
                pb.directory() != null ? pb.directory() : "(default)");
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
        return turnInternal0(messages, first, deltaConsumer, null);
    }

    /**
     * CR-108: 텍스트 델타 + CLI 내부 도구 관찰을 turn 도중에 실시간으로 흘리는 스트리밍 턴.
     * {@code observeConsumer} 는 tool_use(관찰 시작, output=null) / tool_result(완료, output 채워짐)를
     * 도착 즉시 수신한다 — AGENT_CALL 진행 중에도 도구 흐름을 실시간 적재하기 위함.
     */
    public LLMResponse turnStream(List<UnifiedMessage> messages, boolean first,
                                   Consumer<String> deltaConsumer,
                                   Consumer<com.platform.llm.model.LLMStreamChunk.ObservedTool> observeConsumer) {
        return turnInternal0(messages, first, deltaConsumer, observeConsumer);
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    /** CR-121: 마지막 turn 활동 시각(epoch ms) — reaper idle 판정용. */
    public long lastActivityMs() {
        return lastActivityMs;
    }

    /** CR-121: 워커 생성 시각(epoch ms). */
    public long createdAtMs() {
        return createdAtMs;
    }

    /** CR-121: 종료된 프로세스 pid(로그/진단용). */
    public long pid() {
        return process != null ? process.pid() : -1L;
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
        return turnInternal0(messages, first, null, null);
    }

    private LLMResponse turnInternal0(List<UnifiedMessage> messages, boolean first,
                                       Consumer<String> deltaConsumer,
                                       Consumer<com.platform.llm.model.LLMStreamChunk.ObservedTool> observeConsumer) {
        if (process == null || !process.isAlive()) {
            throw new ClaudeCliException("Worker not running");
        }
        turnLock.lock();
        long start = System.currentTimeMillis();
        lastActivityMs = start; // CR-121: reaper idle 판정 갱신
        try {
            if (first && firstTurnSent) {
                throw new IllegalStateException("turnFirst already called on this worker");
            }
            if (!first && !firstTurnSent) {
                throw new IllegalStateException("turn() called before turnFirst()");
            }

            eventQueue.clear();
            writeUserMessages(first ? messages : trimToCurrentTurn(messages));
            if (first) firstTurnSent = true;

            return awaitResult(start, deltaConsumer, observeConsumer);
        } catch (IOException e) {
            throw new ClaudeCliException("stdin write failed: " + e.getMessage(), e);
        } finally {
            turnLock.unlock();
        }
    }

    /**
     * 2턴째 이후 입력을 "이번 턴 몫"으로 자른다.
     *
     * <p>워커는 {@code --resume} 으로 자기 세션 이력을 이미 들고 있으므로, 호출자가 넘긴 전체 대화
     * 이력을 그대로 stdin 에 쓰면 <b>과거 사용자 질문이 매 턴 재전송</b>된다. CLI 입장에서는 이미 답한
     * 질문을 다시 받은 것이라 앞선 지시부터 재수행하고, 정작 이번 질문은 묻힌다(운영 사례:
     * "/tmp 가 어디냐" 질문에 엑셀·PPT 를 다시 만들어 답한 세션 sess-1785039270248-pyhw08hb).
     * 턴이 쌓일수록 재전송 줄 수가 늘어 응답 시간도 함께 악화된다.
     *
     * <p>비스트리밍 경로({@code RunnerService.runTurn})는 호출 전에 {@code lastUserMessage} 로 이미
     * 1개만 뽑아 넘겨 우연히 정상이었고, 스트리밍 경로만 전체를 넘겨 증상이 났다. 절단을 워커 안으로
     * 옮겨 두 경로가 같은 규칙을 따르게 한다.
     *
     * <p>TOOL_RESULT 는 도구 루프가 진행 중인 이번 턴의 입력이므로 보존한다. 마지막 USER 메시지보다
     * 뒤에 오는 TOOL_RESULT 만 남기고, 그 앞의 과거 이력은 버린다.
     */
    // visible for testing
    static List<UnifiedMessage> trimToCurrentTurn(List<UnifiedMessage> messages) {
        if (messages == null || messages.isEmpty()) return messages;
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            UnifiedMessage m = messages.get(i);
            if (m != null && m.role() == UnifiedMessage.Role.USER) {
                lastUserIdx = i;
                break;
            }
        }
        // USER 가 없으면 도구 루프 중(TOOL_RESULT 만 재주입) — 전량 보존.
        if (lastUserIdx < 0) return messages;

        List<UnifiedMessage> trimmed = new ArrayList<>();
        for (int i = lastUserIdx; i < messages.size(); i++) {
            UnifiedMessage m = messages.get(i);
            if (m == null) continue;
            if (m.role() == UnifiedMessage.Role.USER || m.role() == UnifiedMessage.Role.TOOL_RESULT) {
                trimmed.add(m);
            }
        }
        int dropped = messages.size() - trimmed.size();
        if (dropped > 0) {
            log.debug("[CLI-STDIN] 이전 턴 이력 {}건 절단 (CLI 세션이 자체 보유) — 이번 턴 {}건만 전송",
                    dropped, trimmed.size());
        }
        return trimmed;
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
                    // CR-098: 멀티모달(Image/Document) 블록이 있으면 stream-json content 배열로 전송.
                    boolean hasMultimodal = msg.content().stream()
                            .anyMatch(b -> b instanceof ContentBlock.Image || b instanceof ContentBlock.Document);
                    String prefix = null;
                    if (!firstUserConsumed && systemPrefix.length() > 0) {
                        prefix = systemPrefix.toString();
                        firstUserConsumed = true;
                    }
                    if (hasMultimodal) {
                        writeUserContentBlocks(msg, prefix);
                    } else {
                        String text = flattenText(msg);
                        if (prefix != null) text = prefix + "\n\n" + text;
                        writeUserLine(text);
                    }
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

    /**
     * CR-098: 멀티모달 USER 메시지를 stream-json content 배열로 전송.
     * claude CLI(2.1.143) stream-json 이 받는 Anthropic content block 포맷 그대로:
     *   {type:"user", message:{role:"user", content:[{type:"text",...},{type:"document",source:{base64}},{type:"image",...}]}}
     * base64 source 만 지원. prefix(시스템 prepend)가 있으면 맨 앞 text 블록으로 추가.
     */
    private void writeUserContentBlocks(UnifiedMessage msg, String prefix) throws IOException {
        java.util.List<Map<String, Object>> blocks = new java.util.ArrayList<>();
        if (prefix != null && !prefix.isBlank()) {
            blocks.add(textBlock(prefix));
        }
        for (ContentBlock b : msg.content()) {
            if (b instanceof ContentBlock.Text t) {
                if (t.text() != null && !t.text().isEmpty()) blocks.add(textBlock(t.text()));
            } else if (b instanceof ContentBlock.Image img && img.isBase64()) {
                blocks.add(sourceBlock("image", img.mediaType(), img.data()));
            } else if (b instanceof ContentBlock.Document doc && doc.isBase64()) {
                blocks.add(sourceBlock("document", doc.mediaType(), doc.data()));
            }
            // URL 방식·기타 블록은 CLI stream-json 미지원 → 생략
        }
        if (blocks.isEmpty()) return;

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "user");
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", blocks);
        event.put("message", message);
        String line = MAPPER.writeValueAsString(event);
        log.info("[CLI-STDIN] writing multimodal user line: blocks={}, jsonLen={}", blocks.size(), line.length());
        stdin.write(line);
        stdin.newLine();
    }

    private static Map<String, Object> textBlock(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "text");
        m.put("text", text);
        return m;
    }

    private static Map<String, Object> sourceBlock(String type, String mediaType, String base64) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "base64");
        source.put("media_type", mediaType);
        source.put("data", base64);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("source", source);
        return m;
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

    private LLMResponse awaitResult(long startMs, Consumer<String> deltaConsumer,
                                     Consumer<com.platform.llm.model.LLMStreamChunk.ObservedTool> observeConsumer) {
        long deadline = startMs + turnTimeout.toMillis();
        StringBuilder assistantBuffer = new StringBuilder();
        TokenUsage usage = new TokenUsage(0, 0);
        double costUsd = 0.0;
        String id = UUID.randomUUID().toString();
        String modelReport = model != null ? model : "anthropic-cli";
        // CR-102: CLI 내부 도구 루프 관찰 — tool_use(assistant 이벤트) ↔ tool_result(user 이벤트) 를
        // tool_use_id 로 페어링해 LLMResponse.observedToolEvents 로 운반 (가시화 전용, 루프 재진입 아님)
        Map<String, PendingToolObservation> pendingTools = new java.util.LinkedHashMap<>();
        List<com.platform.llm.model.ObservedToolEvent> observedTools = new ArrayList<>();

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
                        // CR-102: 관찰 등록 — tool_result(user 이벤트) 도착 시 페어링
                        for (ToolCall t : tu) {
                            if (t.id() != null) {
                                pendingTools.put(t.id(), new PendingToolObservation(
                                        t.name(), t.input(), System.currentTimeMillis()));
                            } else {
                                observedTools.add(new com.platform.llm.model.ObservedToolEvent(
                                        t.name(), t.input(), null, null));
                            }
                            // CR-108: tool_use 도착 즉시 실시간 방출 (output 미수신 → null).
                            if (observeConsumer != null) {
                                observeConsumer.accept(new com.platform.llm.model.LLMStreamChunk.ObservedTool(
                                        t.id(), t.name(), t.input(), null, null));
                            }
                        }
                    }
                }
                case "user" -> {
                    // CR-102: CLI 가 내부 도구 결과를 user 메시지(tool_result 블록)로 transcript 에 흘린다
                    for (ToolResultObservation tr : extractToolResults(json)) {
                        PendingToolObservation p = pendingTools.remove(tr.toolUseId());
                        if (p != null) {
                            long durMs = System.currentTimeMillis() - p.startMs();
                            observedTools.add(new com.platform.llm.model.ObservedToolEvent(
                                    p.name(), p.input(), tr.output(), durMs));
                            // CR-108: tool_result 도착 즉시 실시간 방출 — output + durationMs 채움.
                            if (observeConsumer != null) {
                                observeConsumer.accept(new com.platform.llm.model.LLMStreamChunk.ObservedTool(
                                        tr.toolUseId(), p.name(), p.input(), tr.output(), durMs));
                            }
                        }
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

                    // CR-112: CLI 가 socket closed / API 에러를 is_error:true 인 result 이벤트로 둔갑시켜
                    // 발행하는 경우(claude code stream-json: subtype=success 라도 isApiError=true 면
                    // is_error:true, error_* subtype 도 is_error:true)를 FinishReason.END 로 정상 반환하면
                    // 호출 측이 COMPLETED 로 오판해 다음 워크플로우 스텝에 쓰레기 입력(에러 텍스트)을 넘긴다.
                    // is_error 가 단일 진실 소스(success/error 모든 subtype 공통) → 예외로 승격해
                    // RunnerService→RunnerController error 이벤트→어댑터→AgentCallStepExecutor→
                    // WorkflowEngine.executeWithRetry(retry/--resume 이어하기) 정책에 태운다.
                    if (Boolean.TRUE.equals(json.get("is_error"))) {
                        String subtype = json.get("subtype") != null ? json.get("subtype").toString() : "unknown";
                        String errText = (finalText != null && !finalText.isBlank())
                                ? finalText : "(no result text)";
                        log.warn("[CLI-RESULT] is_error=true (subtype={}, latency={}ms): {}",
                                subtype, latency,
                                errText.length() > 300 ? errText.substring(0, 300) + "..." : errText);
                        throw new ClaudeCliException(
                                "CLI result reported is_error=true (subtype=" + subtype + "): " + errText);
                    }

                    // CR-050 Phase 9: CLI 가 도구 루프를 자체적으로 완결하므로 외부에는 항상
                    // 최종 텍스트 + finishReason=END 만 노출. tool_use 는 toolCalls 에 싣지 않는다
                    // (실으면 외부 도구 루프가 재진입 시도 — CLI 내부 완결과 충돌).
                    // CR-102: 대신 관찰 전용 observedToolEvents 로 운반 — run 타임라인 가시화용.
                    java.util.List<ContentBlock> content = new ArrayList<>();
                    content.add(new ContentBlock.Text(finalText != null ? finalText : ""));

                    // tool_result 미도착 분(비정상 종료 등)도 input 만이라도 관찰에 포함
                    for (PendingToolObservation p : pendingTools.values()) {
                        observedTools.add(new com.platform.llm.model.ObservedToolEvent(
                                p.name(), p.input(), null, null));
                    }

                    return new LLMResponse(
                            id,
                            modelReport,
                            content,
                            java.util.List.of(),
                            usage,
                            LLMResponse.FinishReason.END,
                            latency,
                            costUsd,
                            observedTools.isEmpty() ? null : java.util.List.copyOf(observedTools)
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

    /** CR-102: tool_use 관찰 대기 항목 (tool_result 페어링 전) */
    private record PendingToolObservation(String name, Map<String, Object> input, long startMs) {}

    /** CR-102: user 이벤트에서 추출한 tool_result 관찰 */
    private record ToolResultObservation(String toolUseId, String output) {}

    /**
     * CR-102: user 이벤트의 content[] 에서 type=tool_result 블록 추출.
     * stream-json 포맷: {type:"user", message:{content:[{type:"tool_result", tool_use_id, content}]}}
     */
    private List<ToolResultObservation> extractToolResults(Map<String, Object> event) {
        Object message = event.get("message");
        if (!(message instanceof Map<?, ?> msg)) return java.util.List.of();
        Object content = msg.get("content");
        if (!(content instanceof List<?> list)) return java.util.List.of();
        List<ToolResultObservation> out = new ArrayList<>();
        for (Object b : list) {
            if (!(b instanceof Map<?, ?> block)) continue;
            if (!"tool_result".equals(block.get("type"))) continue;
            Object tid = block.get("tool_use_id");
            if (tid == null) continue;
            out.add(new ToolResultObservation(tid.toString(), flattenToolResultContent(block.get("content"))));
        }
        return out;
    }

    /** tool_result content 는 string 또는 [{type:text,text}] 배열 — 텍스트로 평탄화 */
    private static String flattenToolResultContent(Object content) {
        if (content == null) return null;
        if (content instanceof String s) return s;
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object b : list) {
                if (b instanceof Map<?, ?> block && "text".equals(block.get("type"))
                        && block.get("text") != null) {
                    sb.append(block.get("text"));
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
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

    /**
     * CR-069: 공통 ClaudeCliCommandBuilder 사용. Worker 는 stream-json + verbose 모드 +
     * connection.config 의 {@code system_prompt_override} 를 CLI {@code --system-prompt}
     * 로 완전 교체한다 (CR-075 보강 — append 만으로는 CLI 본래의 코딩 에이전트 행동을
     * 못 덮으므로 override 가 필요).
     * tool_mode 는 application.yml 의 platform.llm.anthropic-cli.tool-mode 가 default,
     * 호출처가 setToolMode(...) 로 override.
     */
    /** CR-117: 서버 MCP 도구가 run 격리 작업장을 보도록 X-Aimbase-Workspace-Path 헤더 키. */
    private static final String WORKSPACE_HEADER = "X-Aimbase-Workspace-Path";

    /**
     * CR-117: MCP config 의 aimbase-server.headers 에 {@code X-Aimbase-Workspace-Path} 헤더를 주입한다.
     *
     * <p>CLI 가 서버 MCP(file_write 등)를 호출할 때 이 헤더로 run 격리 cwd 를 전달 →
     * {@link com.platform.mcp.server.ServerMcpToolDispatcher} 가 ToolContext.workspacePath 로 채워
     * 내장 도구 cwd 와 동일 작업장을 보게 한다. CR-107 이 cwd(pb.directory)만 전파하고
     * 서버 MCP 도구는 default/general 폴백을 타던 이원화를 해소.
     *
     * <p>workingDir 가 null 이거나 mcpConfig 에 aimbase-server 항목이 없으면 원본을 그대로 반환(기존 동작 보존).
     */
    @SuppressWarnings("unchecked")
    private static String injectWorkspaceHeader(String mcpConfig, String workingDir) {
        if (workingDir == null || workingDir.isBlank()
                || mcpConfig == null || mcpConfig.isBlank()) {
            return mcpConfig;
        }
        try {
            Map<String, Object> root = MAPPER.readValue(mcpConfig, MAP_TYPE);
            Object serversObj = root.get("mcpServers");
            if (!(serversObj instanceof Map)) {
                return mcpConfig;
            }
            Map<String, Object> servers = (Map<String, Object>) serversObj;
            Object serverObj = servers.get("aimbase-server");
            if (!(serverObj instanceof Map)) {
                return mcpConfig;   // 서버 MCP 미노출 (NATIVE/stdio-only) — 헤더 불필요
            }
            Map<String, Object> server = (Map<String, Object>) serverObj;
            Object headersObj = server.get("headers");
            Map<String, Object> headers = (headersObj instanceof Map)
                    ? (Map<String, Object>) headersObj
                    : new LinkedHashMap<>();
            headers.put(WORKSPACE_HEADER, workingDir);
            server.put("headers", headers);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            // 파싱/직렬화 실패 시 원본 유지 — 기능 안전 폴백.
            log.warn("ClaudeCliWorker: failed to inject workspace header into MCP config — using original: {}",
                    e.getMessage());
            return mcpConfig;
        }
    }

    private List<String> buildCommand() {
        // CR-104: 원본 도구명 → mcp__aimbase-server__<tool> 로 변환해 --allowedTools 주입.
        // CLI 가 MCP 서버에서 받는 도구는 server prefix 가 붙으므로 allowedTools 매칭도 같은 prefix 필요.
        // 비어있으면 null → 빌더 미적용(endpoint 노출 전체 사용).
        List<String> mcpAllowed = null;
        if (allowedToolNames != null && !allowedToolNames.isEmpty()) {
            mcpAllowed = allowedToolNames.stream()
                    .map(n -> MCP_SERVER_PREFIX + n)
                    .toList();
        }
        // CR-117: subagent OFF 면 CLI 본체 Agent 도구를 --disallowedTools 로 차단(자율 서브에이전트 spawn 방지).
        // 기본(disallowSubagent=false)이면 null → 빌더 미적용 = 현행 동작(subagent 허용).
        List<String> disallowed = disallowSubagent ? List.of("Agent") : null;
        return ClaudeCliCommandBuilder.builder(binaryPath)
                .toolMode(toolMode)               // null 이면 빌더 default(AIMBASE)
                .sealedNativeTools(sealedNativeTools)  // CR-126: null 이면 빌더 기본 상수
                .mcpConfigJson(injectWorkspaceHeader(mcpConfigJson, workingDirectory))
                .allowedTools(mcpAllowed)         // CR-104: null 이면 빌더가 무시
                .disallowedTools(disallowed)      // CR-117: null 이면 빌더가 무시
                .streamJson(true)
                .verbose(true)
                .resume(resumeSessionId, forkSession)
                .systemPromptOverride(systemPromptOverride)
                .model(model)
                .build();
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
