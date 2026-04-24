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

    @Override
    public Object transformToolDefs(List<UnifiedToolDef> tools) {
        // 도구 봉인 — CLI 에 빈 --tools "" 로 넘기며, 호출 측이 tools 를 넣어도 무시.
        if (tools != null && !tools.isEmpty()) {
            log.debug("ClaudeCliLlmAdapter ignores {} tool defs (--tools '' policy)", tools.size());
        }
        return null;
    }

    @Override
    public List<ToolCall> parseToolCalls(Object nativeResponse) {
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

        ClaudeCliWorker worker;
        try {
            worker = workerPool.getOrCreateMain(runId, model, configDir);
        } catch (ClaudeCliException e) {
            throw e;
        } catch (Exception e) {
            throw new ClaudeCliException("Failed to obtain CLI worker for session " + runId, e);
        }

        boolean firstTurn = firstTurnMarker.putIfAbsent(runId, Boolean.TRUE) == null;
        try {
            if (firstTurn) {
                return deltaConsumer == null
                        ? worker.turnFirst(request.messages())
                        : worker.turnStream(request.messages(), true, deltaConsumer);
            }
            UnifiedMessage last = lastUserMessage(request.messages());
            if (last == null) {
                throw new ClaudeCliException("No user message in subsequent turn");
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
