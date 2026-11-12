package com.platform.orchestrator.approval;

import com.platform.config.PlatformSettingsService;
import com.platform.domain.PendingApprovalEntity;
import com.platform.orchestrator.approval.ChatTurnRegistry.ChatTurn;
import com.platform.orchestrator.stream.StreamEvent;
import com.platform.policy.PolicyEngine;
import com.platform.policy.PolicyEngine.ToolCallDecision;
import com.platform.repository.PendingApprovalRepository;
import com.platform.session.CancellationRegistry;
import com.platform.tool.ToolExecutor;
import com.platform.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CR-185 PRD-563, BIZ-240~242: 도구를 실행하기 직전에 정책을 보고, 승인이 필요하면 대화 스트림으로 묻고 기다린다.
 *
 * <p>API 연결은 도구 루프가, CLI 연결은 서버 MCP 도구 호출이 같은 관문을 지난다. 열린 대화 턴이 없으면 물을 사람이
 * 없으므로 실행하지 않는다(BIZ-241).
 */
@Component
public class ToolApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalGate.class);

    public static final String CHANNEL_CHAT = "chat";
    public static final String TIMEOUT_KEY = "chat.approval-timeout-seconds";
    static final int TIMEOUT_DEFAULT = 120;
    static final int TIMEOUT_MIN = 10;
    static final int TIMEOUT_MAX = 240;
    static final int INPUT_VALUE_MAX_CHARS = 2000;

    // AI 가 받는 도구 결과 문구. AI 가 이것을 읽고 사용자에게 다음 말을 잇는다.
    static final String MSG_DENIED = "정책으로 막힌 도구라 실행하지 않았습니다";
    static final String MSG_CHAT_ONLY = "승인이 필요한 도구라 위젯 대화에서만 실행할 수 있습니다";
    static final String MSG_REJECTED = "사용자가 실행을 거절했습니다";
    static final String MSG_TIMEOUT = "승인 시간이 지나 실행하지 않았습니다";
    static final String MSG_CANCELLED = "응답이 중지되어 실행하지 않았습니다";

    /** proceed 면 실행한다. 아니면 refusal 을 도구 결과로 돌려준다. */
    public record Verdict(boolean proceed, String refusal) {
        public static Verdict go() { return new Verdict(true, null); }
        public static Verdict refuse(String message) { return new Verdict(false, message); }
    }

    private final PolicyEngine policyEngine;
    private final ChatTurnRegistry turns;
    private final PendingApprovalRepository approvals;
    private final PlatformSettingsService settings;
    private final CancellationRegistry cancellationRegistry;
    private final ToolRegistry toolRegistry;
    private final Clock clock;
    private final long sliceMillis;
    private final long keepAliveMillis;

    @Autowired
    public ToolApprovalGate(PolicyEngine policyEngine, ChatTurnRegistry turns,
                            PendingApprovalRepository approvals, PlatformSettingsService settings,
                            CancellationRegistry cancellationRegistry, @Lazy ToolRegistry toolRegistry) {
        this(policyEngine, turns, approvals, settings, cancellationRegistry, toolRegistry,
                Clock.systemUTC(), 1000L, 15_000L);
    }

    ToolApprovalGate(PolicyEngine policyEngine, ChatTurnRegistry turns,
                     PendingApprovalRepository approvals, PlatformSettingsService settings,
                     CancellationRegistry cancellationRegistry, ToolRegistry toolRegistry,
                     Clock clock, long sliceMillis, long keepAliveMillis) {
        this.policyEngine = policyEngine;
        this.turns = turns;
        this.approvals = approvals;
        this.settings = settings;
        this.cancellationRegistry = cancellationRegistry;
        this.toolRegistry = toolRegistry;
        this.clock = clock;
        this.sliceMillis = sliceMillis;
        this.keepAliveMillis = keepAliveMillis;
    }

    /** 정책이 이 도구를 대상으로 두었으면 물을 수 있다. 도구 루프가 이런 도구를 병렬 실행에서 빼 차례로 묻는다. */
    public boolean mayAsk(String toolName) {
        return toolName != null && policyEngine.hasToolPolicy(toolName);
    }

    public Verdict check(String toolName, Map<String, Object> input, String toolUseId, String sessionId) {
        ToolCallDecision decision = policyEngine.evaluateToolCall(toolName, sessionId);
        switch (decision.action()) {
            case ALLOW:
                return Verdict.go();
            case DENY:
                return Verdict.refuse(decision.message() != null ? decision.message() : MSG_DENIED);
            default:
                break;
        }
        ChatTurn turn = turns.get(sessionId);
        if (turn == null) {
            log.info("CR-185 승인 필요 도구를 열린 대화 턴 없이 불러 막음: tool={}, session={}", toolName, sessionId);
            return Verdict.refuse(MSG_CHAT_ONLY);
        }
        return ask(turn, toolName, input, toolUseId, decision);
    }

    private Verdict ask(ChatTurn turn, String toolName, Map<String, Object> input, String toolUseId,
                        ToolCallDecision decision) {
        int timeoutSeconds = Math.max(TIMEOUT_MIN,
                Math.min(TIMEOUT_MAX, settings.getInt(TIMEOUT_KEY, TIMEOUT_DEFAULT)));
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime expiresAt = now.plusSeconds(timeoutSeconds);
        Map<String, Object> shownInput = cutValues(input);

        PendingApprovalEntity row = new PendingApprovalEntity();
        row.setApprovalChannel(CHANNEL_CHAT);
        row.setPolicyId(decision.policyId());
        row.setSessionId(turn.sessionId());
        row.setToolName(toolName);
        row.setToolInput(shownInput);
        row.setRequestedBy(turn.requester());
        row.setRequestMessage(decision.message());
        row.setApprovers(turn.requester() != null ? List.of(turn.requester()) : List.of());
        row.setRequestedAt(now);
        row.setTimeoutAt(expiresAt);
        UUID approvalId = approvals.save(row).getId();

        CompletableFuture<String> future = turns.await(turn, approvalId);
        String outcome;
        try {
            boolean sent = turn.emit(new StreamEvent.ApprovalRequired(
                    approvalId.toString(), toolUseId, toolName, describe(toolName), shownInput,
                    decision.message(), expiresAt.toString(), timeoutSeconds));
            outcome = sent ? waitFor(turn, future, expiresAt) : ChatTurnRegistry.CANCELLED;
        } finally {
            turns.forget(turn, approvalId);
        }

        String status = settle(approvalId, outcome);
        OffsetDateTime decidedAt = approvals.findById(approvalId)
                .map(PendingApprovalEntity::getResolvedAt).orElse(null);
        turn.emit(new StreamEvent.ApprovalResolved(approvalId.toString(), status,
                (decidedAt != null ? decidedAt : OffsetDateTime.now(clock)).toString()));
        log.info("CR-185 대화 승인 {}: tool={}, session={}, approval={}", status, toolName, turn.sessionId(), approvalId);

        return switch (status) {
            case ChatTurnRegistry.APPROVED -> Verdict.go();
            case ChatTurnRegistry.REJECTED -> Verdict.refuse(MSG_REJECTED);
            case ChatTurnRegistry.TIMEOUT -> Verdict.refuse(MSG_TIMEOUT);
            default -> {
                // 중지나 끊김이면 그 턴을 끝낸다. 듣는 사람이 없는데 AI 가 답을 이어 가면 쓸모없이 돈다.
                cancellationRegistry.cancel(turn.cancelled());
                yield Verdict.refuse(MSG_CANCELLED);
            }
        };
    }

    private String waitFor(ChatTurn turn, CompletableFuture<String> future, OffsetDateTime expiresAt) {
        long lastBeat = clock.millis();
        while (true) {
            try {
                return future.get(sliceMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // 아래에서 중지, 마감, 끊김을 본다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ChatTurnRegistry.CANCELLED;
            } catch (ExecutionException e) {
                return ChatTurnRegistry.CANCELLED;
            }
            if (turn.cancelled().get()) return ChatTurnRegistry.CANCELLED;
            if (!OffsetDateTime.now(clock).isBefore(expiresAt)) return ChatTurnRegistry.TIMEOUT;
            if (clock.millis() - lastBeat >= keepAliveMillis) {
                AtomicBoolean lost = new AtomicBoolean(false);
                if (!turn.emit(new StreamEvent.KeepAlive(lost)) || lost.get()) return ChatTurnRegistry.CANCELLED;
                lastBeat = clock.millis();
            }
        }
    }

    /**
     * 결정 API 가 정한 것(approved, rejected)은 이미 적혀 있다. 시간 지남과 중지는 여기서 닫는다.
     * 그 순간 결정 API 가 먼저 닫았으면(0건) 적힌 상태를 따른다.
     */
    private String settle(UUID approvalId, String outcome) {
        if (!ChatTurnRegistry.TIMEOUT.equals(outcome) && !ChatTurnRegistry.CANCELLED.equals(outcome)) {
            return outcome;
        }
        int closed = approvals.closeIfPending(approvalId, outcome, null, null, OffsetDateTime.now(clock));
        if (closed > 0) return outcome;
        return approvals.findById(approvalId).map(PendingApprovalEntity::getStatus).orElse(outcome);
    }

    private String describe(String toolName) {
        ToolExecutor executor = toolRegistry.getExecutor(toolName);
        if (executor == null || executor.getDefinition() == null) return null;
        String description = executor.getDefinition().description();
        if (description == null || description.isBlank()) return null;
        String first = description.strip().lines().findFirst().orElse("");
        return first.isBlank() ? null : first;
    }

    /** 카드와 DB 에 담는 입력. 값마다 잘라 큰 본문(파일 내용 등)이 카드와 행을 키우지 않게 한다. */
    static Map<String, Object> cutValues(Map<String, Object> input) {
        if (input == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : input.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s && s.length() > INPUT_VALUE_MAX_CHARS) {
                v = s.substring(0, INPUT_VALUE_MAX_CHARS);
            } else if (v != null && !(v instanceof String) && !(v instanceof Number) && !(v instanceof Boolean)) {
                String text = v.toString();
                if (text.length() > INPUT_VALUE_MAX_CHARS) v = text.substring(0, INPUT_VALUE_MAX_CHARS);
            }
            out.put(e.getKey(), v);
        }
        return out;
    }
}
