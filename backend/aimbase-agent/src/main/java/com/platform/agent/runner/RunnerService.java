package com.platform.agent.runner;

import com.platform.llm.model.LLMRequest;
import com.platform.llm.model.LLMResponse;
import com.platform.llm.model.UnifiedMessage;
import com.platform.runner.claudecli.ClaudeCliCommandBuilder;
import com.platform.runner.claudecli.ClaudeCliWorker;
import com.platform.runner.claudecli.ClaudeCliWorkerPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        return runTurn(request, toolMode, configDir, systemPromptOverride, null);
    }

    public LLMResponse chatStream(LLMRequest request, ClaudeCliCommandBuilder.ToolMode toolMode,
                                  String configDir, String systemPromptOverride,
                                  Consumer<String> deltaConsumer) {
        return runTurn(request, toolMode, configDir, systemPromptOverride, deltaConsumer);
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

    public int activeRunCount() {
        return workerPool.activeRunCount();
    }

    // ─── 내부 ─────────────────────────────────────────────────────────────

    private LLMResponse runTurn(LLMRequest request, ClaudeCliCommandBuilder.ToolMode toolMode,
                                String configDir, String systemPromptOverride,
                                Consumer<String> deltaConsumer) {
        String runId = request.sessionId();
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("run_id (sessionId) required");
        }
        String model = (request.model() != null && !request.model().isBlank())
                ? request.model() : defaultModel;
        ClaudeCliWorker worker = workerPool.getOrCreateMain(runId, model, configDir);
        if (toolMode != null) {
            worker.setToolMode(toolMode);
        }
        if (systemPromptOverride != null && !systemPromptOverride.isBlank()) {
            worker.setSystemPromptOverride(systemPromptOverride);
        }

        boolean isFirst = firstTurnDone.putIfAbsent(runId, Boolean.TRUE) == null;
        List<UnifiedMessage> messages = request.messages();

        try {
            if (deltaConsumer != null) {
                return worker.turnStream(messages, isFirst, deltaConsumer);
            }
            if (isFirst) {
                return worker.turnFirst(messages);
            }
            UnifiedMessage lastUser = lastUserMessage(messages);
            return worker.turn(lastUser);
        } catch (RuntimeException e) {
            // 예외 시 다음 호출에서 재초기화 가능하도록 마커 회수
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
        String text = (content instanceof String s) ? s
                : (content == null ? "" : content.toString());
        UnifiedMessage.Role r = switch (role) {
            case "system" -> UnifiedMessage.Role.SYSTEM;
            case "assistant" -> UnifiedMessage.Role.ASSISTANT;
            case "tool", "tool_result" -> UnifiedMessage.Role.TOOL_RESULT;
            default -> UnifiedMessage.Role.USER;
        };
        return UnifiedMessage.ofText(r, text);
    }
}
