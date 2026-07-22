package com.platform.agent.runner;

import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.UnifiedMessage;
import com.platform.runner.claudecli.ClaudeCliCommandBuilder;
import com.platform.runner.claudecli.ClaudeCliWorker;
import com.platform.runner.claudecli.ClaudeCliWorkerPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * CR-071 Phase 2: ClaudeCliRunner 서비스 — Worker Pool 위 thin 래퍼.
 *
 * <p>RunnerController 가 호출. 메시지 시퀀스를 Worker 의 turnFirst / turn 으로 매핑.
 *
 * <p>같은 {@code runId} 의 연속 호출은 같은 Worker 재사용 — Worker 가 CLI 세션을 유지한다.
 */
public class RunnerService {

    private static final Logger log = LoggerFactory.getLogger(RunnerService.class);

    private final ClaudeCliWorkerPool workerPool;
    private final String defaultModel;

    /** runId → 첫 턴이 끝났는지. */
    private final ConcurrentHashMap<String, Boolean> firstTurnDone = new ConcurrentHashMap<>();

    public RunnerService(ClaudeCliWorkerPool workerPool, String defaultModel) {
        this.workerPool = workerPool;
        this.defaultModel = defaultModel;
    }

    public LLMResponse chat(LLMRequest request, ClaudeCliCommandBuilder.ToolMode toolMode,
                            String configDir, String systemPromptOverride) {
        return chat(request, toolMode, configDir, systemPromptOverride, null);
    }

    /** CR-104: allowedTools(원본 도구명) 전달 오버로드. */
    public LLMResponse chat(LLMRequest request, ClaudeCliCommandBuilder.ToolMode toolMode,
                            String configDir, String systemPromptOverride, List<String> allowedTools) {
        return chat(request, toolMode, configDir, systemPromptOverride, allowedTools, false);
    }

    /** CR-117: disallowSubagent(CLI 본체 Agent 서브에이전트 차단) 전달 오버로드. */
    public LLMResponse chat(LLMRequest request, ClaudeCliCommandBuilder.ToolMode toolMode,
                            String configDir, String systemPromptOverride, List<String> allowedTools,
                            boolean disallowSubagent) {
        return runTurn(request, toolMode, configDir, systemPromptOverride, null, allowedTools, null, disallowSubagent);
    }

    public LLMResponse chatStream(LLMRequest request, ClaudeCliCommandBuilder.ToolMode toolMode,
                                  String configDir, String systemPromptOverride,
                                  Consumer<String> deltaConsumer) {
        return chatStream(request, toolMode, configDir, systemPromptOverride, deltaConsumer, null);
    }

    /** CR-104: allowedTools(원본 도구명) 전달 오버로드. */
    public LLMResponse chatStream(LLMRequest request, ClaudeCliCommandBuilder.ToolMode toolMode,
                                  String configDir, String systemPromptOverride,
                                  Consumer<String> deltaConsumer, List<String> allowedTools) {
        return runTurn(request, toolMode, configDir, systemPromptOverride, deltaConsumer, allowedTools, null, false);
    }

    /** CR-108: 도구 관찰 실시간 콜백(observeConsumer) 전달 오버로드. */
    public LLMResponse chatStream(LLMRequest request, ClaudeCliCommandBuilder.ToolMode toolMode,
                                  String configDir, String systemPromptOverride,
                                  Consumer<String> deltaConsumer, List<String> allowedTools,
                                  Consumer<com.platform.llm.model.LLMStreamChunk.ObservedTool> observeConsumer) {
        return chatStream(request, toolMode, configDir, systemPromptOverride,
                deltaConsumer, allowedTools, observeConsumer, false);
    }

    /** CR-117: disallowSubagent(CLI 본체 Agent 서브에이전트 차단) 전달 오버로드. */
    public LLMResponse chatStream(LLMRequest request, ClaudeCliCommandBuilder.ToolMode toolMode,
                                  String configDir, String systemPromptOverride,
                                  Consumer<String> deltaConsumer, List<String> allowedTools,
                                  Consumer<com.platform.llm.model.LLMStreamChunk.ObservedTool> observeConsumer,
                                  boolean disallowSubagent) {
        return runTurn(request, toolMode, configDir, systemPromptOverride,
                deltaConsumer, allowedTools, observeConsumer, disallowSubagent);
    }

    /**
     * 진행 중인 run 의 워커를 강제 종료. Worker close + Pool 에서 invalidate.
     */
    public boolean cancel(String runId) {
        if (runId == null || runId.isBlank()) return false;
        try {
            workerPool.shutdownForRun(runId);
            firstTurnDone.remove(runId);
            return true;
        } catch (Exception e) {
            log.warn("cancel({}) 실패: {}", runId, e.getMessage());
            return false;
        }
    }

    /**
     * CR-121: 접두사로 시작하는 모든 run 의 워커를 일괄 종료한다. LARGE_INPUT 은 청크/재시도마다
     * runId(={parentRunId}-li-...)가 달라 pool 에 제각각 등록되므로, 부모 runId 접두사 하나로 모두 회수한다.
     *
     * @return 종료한 run(워커 그룹) 수
     */
    public int cancelByPrefix(String runIdPrefix) {
        if (runIdPrefix == null || runIdPrefix.isBlank()) return 0;
        try {
            int closed = workerPool.shutdownForRunPrefix(runIdPrefix);
            firstTurnDone.keySet().removeIf(k -> k != null && k.startsWith(runIdPrefix));
            if (closed > 0) log.info("cancelByPrefix({}): {} run 워커 그룹 종료", runIdPrefix, closed);
            return closed;
        } catch (Exception e) {
            log.warn("cancelByPrefix({}) 실패: {}", runIdPrefix, e.getMessage());
            return 0;
        }
    }

    public int activeRunCount() {
        return workerPool.activeRunCount();
    }

    // ─── 내부 ─────────────────────────────────────────────────────────────

    private LLMResponse runTurn(LLMRequest request, ClaudeCliCommandBuilder.ToolMode toolMode,
                                String configDir, String systemPromptOverride,
                                Consumer<String> deltaConsumer, List<String> allowedTools,
                                Consumer<com.platform.llm.model.LLMStreamChunk.ObservedTool> observeConsumer,
                                boolean disallowSubagent) {
        String runId = request.sessionId();
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("run_id (sessionId) required");
        }
        String model = (request.model() != null && !request.model().isBlank())
                ? request.model() : defaultModel;
        // Pool 의 오버로드: spawn 시점에 systemPrompt/toolMode/allowedTools 모두 적용된다.
        // 같은 runId 재호출 시 Worker 가 이미 살아있으면 세 인자는 무시 (Pool 정책 — run 단위 도구 집합 고정).
        // CR-104: allowedTools = 호출의 도구 목록(원본명) → Worker 가 mcp__aimbase-server__ 변환 후 --allowedTools.
        ClaudeCliWorker worker = workerPool.getOrCreateMain(
                runId, model, configDir, systemPromptOverride, toolMode, allowedTools,
                request.workingDirectory(), disallowSubagent);

        boolean isFirst = firstTurnDone.putIfAbsent(runId, Boolean.TRUE) == null;
        List<UnifiedMessage> messages = request.messages();

        try {
            // CR-108: deltaConsumer 또는 observeConsumer 중 하나라도 있으면 스트림 경로
            // (observeConsumer 만 있어도 turn 도중 도구 관찰을 실시간 흘려야 하므로).
            if (deltaConsumer != null || observeConsumer != null) {
                return worker.turnStream(messages, isFirst, deltaConsumer, observeConsumer);
            }
            if (isFirst) {
                return worker.turnFirst(messages);
            }
            UnifiedMessage lastUser = lastUserMessage(messages);
            return worker.turn(lastUser);
        } catch (RuntimeException e) {
            // CR-109: 예외(특히 turn timeout = ClaudeCliTimeoutException) 시 worker 를 닫는다.
            // 닫지 않으면 살아있는 claude CLI 프로세스가 누수된다(운영 좀비 누적). shutdownForRun 이
            // runs 맵에서 RunWorkers 를 제거 + 모든 worker.close() 까지 수행하므로, 다음 호출은
            // getOrCreateMain 에서 새 worker 로 재초기화된다(재시도 가능).
            try {
                workerPool.shutdownForRun(runId);
            } catch (Exception ce) {
                log.warn("runTurn 예외 정리 중 shutdownForRun({}) 실패: {}", runId, ce.getMessage());
            }
            firstTurnDone.remove(runId);
            throw e;
        }
    }

    private static UnifiedMessage lastUserMessage(List<UnifiedMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages empty");
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            UnifiedMessage m = messages.get(i);
            if (m != null && m.role() == UnifiedMessage.Role.USER) {
                return m;
            }
        }
        return messages.get(messages.size() - 1);
    }

    /**
     * 헬퍼: dto 페이로드(role/content Map 리스트) 를 UnifiedMessage 리스트로 변환.
     * Controller 에서 사용. content 는 텍스트 한 줄로 단순화 (멀티모달은 Phase 4 어댑터에서 처리).
     */
    public static List<UnifiedMessage> mapMessages(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        return raw.stream().map(RunnerService::toUnified).toList();
    }

    private static UnifiedMessage toUnified(Map<String, Object> m) {
        String role = m.get("role") != null ? m.get("role").toString().toLowerCase() : "user";
        Object content = m.get("content");
        UnifiedMessage.Role r = switch (role) {
            case "system" -> UnifiedMessage.Role.SYSTEM;
            case "assistant" -> UnifiedMessage.Role.ASSISTANT;
            case "tool", "tool_result" -> UnifiedMessage.Role.TOOL_RESULT;
            default -> UnifiedMessage.Role.USER;
        };

        // CR-098: content 가 블록 배열(멀티모달)이면 ContentBlock 으로 복원, 문자열이면 텍스트(하위호환).
        if (content instanceof List<?> blocks && r == UnifiedMessage.Role.USER) {
            List<ContentBlock> restored = new ArrayList<>();
            for (Object o : blocks) {
                if (o instanceof Map<?, ?> blk) {
                    ContentBlock cb = toContentBlock(blk);
                    if (cb != null) restored.add(cb);
                }
            }
            if (!restored.isEmpty()) {
                return UnifiedMessage.ofUserContent(restored);
            }
        }

        String text = (content instanceof String s) ? s
                : (content == null ? "" : content.toString());
        return UnifiedMessage.ofText(r, text);
    }

    /**
     * CR-098: Anthropic content block(Map) → ContentBlock. base64 source 만. text/image/document 지원.
     */
    @SuppressWarnings("unchecked")
    private static ContentBlock toContentBlock(Map<?, ?> blk) {
        Object type = blk.get("type");
        if (type == null) return null;
        switch (type.toString()) {
            case "text" -> {
                Object t = blk.get("text");
                return new ContentBlock.Text(t != null ? t.toString() : "");
            }
            case "image" -> {
                Map<String, Object> src = (Map<String, Object>) blk.get("source");
                if (src == null) return null;
                return ContentBlock.Image.ofBase64(str(src.get("media_type")), str(src.get("data")));
            }
            case "document" -> {
                Map<String, Object> src = (Map<String, Object>) blk.get("source");
                if (src == null) return null;
                return ContentBlock.Document.ofBase64(str(src.get("media_type")), str(src.get("data")), null);
            }
            default -> {
                return null;
            }
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }
}
