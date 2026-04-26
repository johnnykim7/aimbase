package com.platform.llm.adapter;

import com.platform.llm.claudecli.ClaudeCliBranchScope;
import com.platform.llm.claudecli.ClaudeCliException;
import com.platform.llm.claudecli.ClaudeCliWorker;
import com.platform.llm.claudecli.ClaudeCliWorkerPool;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.LLMStreamChunk;
import com.platform.llm.model.ToolCall;
import com.platform.llm.model.UnifiedMessage;
import com.platform.tool.model.UnifiedToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * CR-050 Phase 3 (PRD-308). LLMAdapter 구현 — Claude CLI 경로.
 *
 * <p>핵심 동작:
 * <ul>
 *   <li>{@link LLMRequest#sessionId()} 를 run 식별자로 사용 — 같은 세션의 연속 호출은 같은 워커 재사용.</li>
 *   <li>첫 턴: 전체 messages NDJSON 주입 ({@link ClaudeCliWorker#turnFirst}).</li>
 *   <li>이후 턴: 마지막 user 메시지만 ({@link ClaudeCliWorker#turn}).</li>
 *   <li>도구 미지원 — {@code --tools ""} 봉인. {@link #transformToolDefs} 는 빈 구현.</li>
 * </ul>
 *
 * <p>Connection 레벨 설정:
 * <ul>
 *   <li>{@code config.model} — 예 {@code claude-sonnet-4-6}. CLI {@code --model} 로 전달.</li>
 *   <li>{@code config.claude_config_dir} — nullable. 지정 시 {@code CLAUDE_CONFIG_DIR} 환경변수.</li>
 * </ul>
 */
public class ClaudeCliLlmAdapter implements LLMAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliLlmAdapter.class);
    public static final String PROVIDER = "anthropic-cli";

    private final ClaudeCliWorkerPool workerPool;
    private final String defaultModel;
    private final String configDir;

    /** runId → 메인 워커가 첫 턴을 이미 돌렸는지. (Worker 내부 상태를 Adapter 에서도 추적) */
    private final ConcurrentMap<String, Boolean> firstTurnMarker = new ConcurrentHashMap<>();

    /** fork 워커가 첫 턴을 이미 돌렸는지 (scopeKey = parentRunId#branchKey 기준) */
    private final ConcurrentMap<String, Boolean> branchFirstTurnMarker = new ConcurrentHashMap<>();

    public ClaudeCliLlmAdapter(ClaudeCliWorkerPool workerPool, String defaultModel, String configDir) {
        this.workerPool = workerPool;
        this.defaultModel = defaultModel;
        this.configDir = configDir;
    }

    @Override
    public String getProvider() {
        return PROVIDER;
    }

    @Override
    public List<String> getSupportedModels() {
        // CLI가 지원하는 모델은 계정 플랜에 따라 달라진다 — 공식 목록 없음. 빈 리스트 반환.
        return List.of();
    }

    @Override
    public CompletableFuture<LLMResponse> chat(LLMRequest request) {
        // CR-050: 호출 스레드에서 직접 실행 — ClaudeCliBranchScope (ThreadLocal) 가 전파되어야 함.
        // CLI 경로는 본질적으로 per-run 워커를 직렬 사용하므로 호출 스레드 실행이 자연스럽다.
        try {
            return CompletableFuture.completedFuture(runTurn(request, null));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void chatStream(LLMRequest request, Consumer<LLMStreamChunk> chunkConsumer) {
        String id = request.sessionId() != null ? request.sessionId() : "cli-stream";
        LLMResponse resp = runTurn(request, delta ->
                chunkConsumer.accept(LLMStreamChunk.text(id, resolveModel(request), delta)));
        chunkConsumer.accept(LLMStreamChunk.done(
                id, resolveModel(request), resp.usage(), resp.finishReason(), resp.toolCalls()));
    }

    /**
     * Claude CLI 는 Anthropic API 의 {@code tools} 파라미터가 없으며, 시스템 프롬프트 텍스트
     * 인젝션으로는 native {@code tool_use} 응답을 끌어낼 수 없다(실측 — Phase 8 우회 시도 실패).
     * <p>도구 통합의 정식 채널은 {@code --mcp-config} 로 연결되는 MCP 서버이다 (CR-050 Phase 9).
     * Aimbase 도구는 {@code aimbase-agent --mcp-stdio} (CR-042/044) 가 노출하며,
     * 워커가 spawn 시 해당 MCP 서버에 자동 연결된다 ({@link com.platform.llm.claudecli.ClaudeCliWorker}
     * 의 buildCommand 참고). 따라서 어댑터 레벨에서 도구 정의를 변환할 필요가 없다.
     *
     * @return 항상 null — 도구는 MCP 채널로 전달됨.
     */
    @Override
    public Object transformToolDefs(List<UnifiedToolDef> tools) {
        return null;
    }

    @Override
    public List<ToolCall> parseToolCalls(Object nativeResponse) {
        // CLI 경로에서는 워커가 이미 LLMResponse.toolCalls 에 채워서 반환한다.
        // 호출자가 LLMResponse 를 그대로 넘기면 거기서 추출.
        if (nativeResponse instanceof LLMResponse resp) {
            return resp.toolCalls() != null ? resp.toolCalls() : List.of();
        }
        return List.of();
    }

    // ─── 내부 ───────────────────────────────────────────────────────────

    private LLMResponse runTurn(LLMRequest request, Consumer<String> deltaConsumer) {
        String runId = request.sessionId();
        if (runId == null || runId.isBlank()) {
            throw new ClaudeCliException(
                    "ClaudeCliLlmAdapter requires LLMRequest.sessionId() as the run scope key");
        }
        String model = resolveModel(request);

        // CR-050: 병렬 브랜치 스코프가 활성이면 fork 워커 경로.
        ClaudeCliBranchScope branch = ClaudeCliBranchScope.current();
        if (branch != null && runId.equals(branch.parentRunId())) {
            return runBranchTurn(request, branch, model, deltaConsumer);
        }

        // CR-068 후속: SYSTEM 메시지를 모아 --system-prompt flag 로 CLI 본체 prompt 교체.
        // 첫 spawn 에만 의미 있고 (기존 worker 재사용 시 무시), API 어댑터와 동등한 통제를 모델에 적용.
        String systemPrompt = collectSystemPrompt(request.messages());

        ClaudeCliWorker worker;
        try {
            worker = workerPool.getOrCreateMain(runId, model, configDir, systemPrompt);
        } catch (ClaudeCliException e) {
            throw e;
        } catch (Exception e) {
            throw new ClaudeCliException("Failed to obtain CLI worker for session " + runId, e);
        }

        boolean firstTurn = firstTurnMarker.putIfAbsent(runId, Boolean.TRUE) == null;
        try {
            if (firstTurn) {
                // Phase 9: 도구는 MCP 채널로 전달되므로 messages 가공 없이 그대로 전달.
                return deltaConsumer == null
                        ? worker.turnFirst(request.messages())
                        : worker.turnStream(request.messages(), true, deltaConsumer);
            }
            // 이후 턴: 마지막 user 또는 tool_result 만 주입.
            UnifiedMessage last = lastNonAssistantMessage(request.messages());
            if (last == null) {
                throw new ClaudeCliException("No user/tool_result message in subsequent turn");
            }
            return deltaConsumer == null
                    ? worker.turn(last)
                    : worker.turnStream(List.of(last), false, deltaConsumer);
        } catch (ClaudeCliException e) {
            if (!worker.isAlive()) {
                log.warn("CLI worker died during turn (runId={}) — invalidating", runId);
                workerPool.invalidateMain(runId);
                firstTurnMarker.remove(runId);
            }
            throw e;
        }
    }

    private UnifiedMessage lastNonAssistantMessage(List<UnifiedMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            UnifiedMessage m = messages.get(i);
            if (m.role() == UnifiedMessage.Role.USER || m.role() == UnifiedMessage.Role.TOOL_RESULT) {
                return m;
            }
        }
        return null;
    }

    /**
     * CR-068: SYSTEM 메시지를 모아 단일 문자열로 반환 (CLI --system-prompt flag 용).
     * 메시지가 없거나 SYSTEM role 이 없으면 null → CLI 기본 system prompt 사용.
     * 여러 SYSTEM 블록은 빈 줄로 join.
     */
    private String collectSystemPrompt(List<UnifiedMessage> messages) {
        if (messages == null || messages.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (UnifiedMessage msg : messages) {
            if (msg.role() != UnifiedMessage.Role.SYSTEM) continue;
            String text = extractText(msg);
            if (text == null || text.isBlank()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(text);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String extractText(UnifiedMessage msg) {
        if (msg == null || msg.content() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (var block : msg.content()) {
            if (block instanceof com.platform.llm.model.ContentBlock.Text t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    /**
     * 병렬 브랜치용 fork 워커 실행 경로.
     *
     * <p>부모 runId 의 메인 워커 session_id 를 확보한 뒤, 같은 branchKey 스코프 내에서는
     * fork 워커 하나를 재사용한다 (스코프 첫 호출에서 spawn, 이후 재사용).
     *
     * <p>중요: 메인 워커가 첫 턴을 아직 안 돌린 상태에서 브랜치가 시작되면 session_id 가
     * 없어 fork 할 수 없다 — 이때는 메인 워커를 먼저 한 번 돌려 session 확보가 필요한데,
     * 현재 설계에서는 병렬 직전 스텝(메인)이 이미 완료되어 session_id 가 채워진 상태를 전제.
     * session_id 가 없으면 메인 워커 경로로 폴백.
     */
    private LLMResponse runBranchTurn(LLMRequest request, ClaudeCliBranchScope branch,
                                       String model, Consumer<String> deltaConsumer) {
        String parentRunId = branch.parentRunId();
        String branchKey = branch.branchKey();
        String scopeKey = parentRunId + "#" + branchKey;

        ClaudeCliWorker mainWorker = workerPool.getOrCreateMain(parentRunId, model, configDir);
        String parentSession = mainWorker.getSessionId();
        if (parentSession == null || parentSession.isBlank()) {
            log.info("Branch '{}' starting without parent session_id — fallback to main-only path",
                    scopeKey);
            // 메인 워커가 아직 session 을 발급 못 한 경우: 경쟁/순서 꼬임 방지를 위해
            // branch 경로를 포기하고 기본 sessionId 로 돌아간다. 이때는 메인 워커가 직렬 사용됨.
            Boolean prev = firstTurnMarker.putIfAbsent(parentRunId, Boolean.TRUE);
            boolean firstTurn = prev == null;
            return firstTurn
                    ? (deltaConsumer == null
                            ? mainWorker.turnFirst(request.messages())
                            : mainWorker.turnStream(request.messages(), true, deltaConsumer))
                    : (deltaConsumer == null
                            ? mainWorker.turn(lastUserMessageOrThrow(request.messages()))
                            : mainWorker.turnStream(
                                    List.of(lastUserMessageOrThrow(request.messages())),
                                    false, deltaConsumer));
        }

        ClaudeCliWorker forkWorker = workerPool.getOrSpawnBranchWorker(
                parentRunId, branchKey, parentSession, model, configDir);

        boolean firstTurn = branchFirstTurnMarker.putIfAbsent(scopeKey, Boolean.TRUE) == null;
        try {
            if (firstTurn) {
                return deltaConsumer == null
                        ? forkWorker.turnFirst(request.messages())
                        : forkWorker.turnStream(request.messages(), true, deltaConsumer);
            }
            UnifiedMessage last = lastUserMessageOrThrow(request.messages());
            return deltaConsumer == null
                    ? forkWorker.turn(last)
                    : forkWorker.turnStream(List.of(last), false, deltaConsumer);
        } catch (ClaudeCliException e) {
            if (!forkWorker.isAlive()) {
                log.warn("Fork worker died during turn (scope={}) — discarding", scopeKey);
                branchFirstTurnMarker.remove(scopeKey);
                workerPool.releaseBranchWorker(parentRunId, branchKey);
            }
            throw e;
        }
    }

    private UnifiedMessage lastUserMessageOrThrow(List<UnifiedMessage> messages) {
        UnifiedMessage last = lastUserMessage(messages);
        if (last == null) throw new ClaudeCliException("No user message in subsequent turn");
        return last;
    }

    /**
     * 브랜치 스코프 종료 시 해당 브랜치의 fork 워커를 풀에 반납.
     * {@link com.platform.workflow.step.ParallelStepExecutor} 가 서브스텝 종료 후 호출.
     */
    public void closeBranch(String parentRunId, String branchKey) {
        String scopeKey = parentRunId + "#" + branchKey;
        branchFirstTurnMarker.remove(scopeKey);
        workerPool.releaseBranchWorker(parentRunId, branchKey);
    }

    private String resolveModel(LLMRequest request) {
        if (request.model() != null && !request.model().isBlank()) return request.model();
        return defaultModel;
    }

    private UnifiedMessage lastUserMessage(List<UnifiedMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == UnifiedMessage.Role.USER) return messages.get(i);
        }
        return null;
    }
}
