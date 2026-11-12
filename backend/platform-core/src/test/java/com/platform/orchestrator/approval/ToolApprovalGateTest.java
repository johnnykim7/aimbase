package com.platform.orchestrator.approval;

import com.platform.config.PlatformSettingsService;
import com.platform.domain.PendingApprovalEntity;
import com.platform.orchestrator.approval.ChatTurnRegistry.ChatTurn;
import com.platform.orchestrator.approval.ToolApprovalGate.Verdict;
import com.platform.orchestrator.stream.StreamEvent;
import com.platform.policy.PolicyEngine;
import com.platform.policy.PolicyEngine.ToolCallDecision;
import com.platform.policy.model.PolicyResult.PolicyAction;
import com.platform.repository.PendingApprovalRepository;
import com.platform.session.CancellationRegistry;
import com.platform.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** CR-185 TC-CHT-111~113: 도구 실행 전 관문. */
class ToolApprovalGateTest {

    private static final String SESSION = "sess-1";
    private static final String TOOL = "send_mail";

    /** 시험이 앞당길 수 있는 시계. 관문의 마감과 keep-alive 간격을 실제로 기다리지 않게 한다. */
    static final class MovableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-13T01:00:00Z");
        void advanceSeconds(long s) { now = now.plusSeconds(s); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private PolicyEngine policyEngine;
    private PendingApprovalRepository approvals;
    private PlatformSettingsService settings;
    private ChatTurnRegistry turns;
    private CancellationRegistry cancellation;
    private MovableClock clock;
    private ToolApprovalGate gate;
    private final AtomicReference<PendingApprovalEntity> saved = new AtomicReference<>();
    private final List<StreamEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        policyEngine = mock(PolicyEngine.class);
        approvals = mock(PendingApprovalRepository.class);
        settings = mock(PlatformSettingsService.class);
        turns = new ChatTurnRegistry();
        cancellation = new CancellationRegistry();
        clock = new MovableClock();
        when(settings.getInt(eq(ToolApprovalGate.TIMEOUT_KEY), anyInt())).thenReturn(120);
        when(approvals.save(any())).thenAnswer(inv -> {
            PendingApprovalEntity row = inv.getArgument(0);
            ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
            saved.set(row);
            return row;
        });
        when(approvals.findById(any())).thenAnswer(inv -> Optional.ofNullable(saved.get()));
        gate = new ToolApprovalGate(policyEngine, turns, approvals, settings, cancellation,
                mock(ToolRegistry.class), clock, 5L, 15_000L);
    }

    private void requireApproval() {
        when(policyEngine.evaluateToolCall(eq(TOOL), any()))
                .thenReturn(new ToolCallDecision(PolicyAction.REQUIRE_APPROVAL, "p-mail", "메일 발송 전 확인"));
    }

    private ChatTurn openTurn(String requester) {
        AtomicBoolean token = cancellation.register(SESSION);
        return turns.open(SESSION, requester, events::add, token);
    }

    private CompletableFuture<Verdict> checkAsync(Map<String, Object> input) {
        return CompletableFuture.supplyAsync(() -> gate.check(TOOL, input, "tu-1", SESSION));
    }

    private UUID awaitRequest() throws Exception {
        for (int i = 0; i < 400; i++) {
            for (StreamEvent e : events) {
                if (e instanceof StreamEvent.ApprovalRequired r) return UUID.fromString(r.approvalId());
            }
            Thread.sleep(5);
        }
        throw new AssertionError("approval_required 가 오지 않음");
    }

    private StreamEvent.ApprovalResolved resolved() {
        return events.stream().filter(e -> e instanceof StreamEvent.ApprovalResolved)
                .map(e -> (StreamEvent.ApprovalResolved) e).findFirst().orElseThrow();
    }

    @Test
    void 정책이_허용하면_묻지_않고_실행한다() {
        when(policyEngine.evaluateToolCall(eq(TOOL), any())).thenReturn(ToolCallDecision.allow());
        assertThat(gate.check(TOOL, Map.of(), "tu-1", SESSION).proceed()).isTrue();
        verify(approvals, never()).save(any());
    }

    @Test
    void 정책이_막으면_안내_문구를_결과로_돌려준다() {
        when(policyEngine.evaluateToolCall(eq(TOOL), any()))
                .thenReturn(new ToolCallDecision(PolicyAction.DENY, "p-x", "외부 발송 금지"));
        Verdict v = gate.check(TOOL, Map.of(), "tu-1", SESSION);
        assertThat(v.proceed()).isFalse();
        assertThat(v.refusal()).isEqualTo("외부 발송 금지");
    }

    @Test
    void TC_CHT_111_열린_턴이_없으면_실행하지_않고_줄도_만들지_않는다() {
        requireApproval();
        Verdict v = gate.check(TOOL, Map.of(), "tu-1", SESSION);
        assertThat(v.proceed()).isFalse();
        assertThat(v.refusal()).isEqualTo(ToolApprovalGate.MSG_CHAT_ONLY);
        verify(approvals, never()).save(any());
    }

    @Test
    void TC_CHT_112_승인하면_줄을_남기고_묻고_실행한다() throws Exception {
        requireApproval();
        openTurn("user-7");
        String longBody = "가".repeat(ToolApprovalGate.INPUT_VALUE_MAX_CHARS + 50);
        CompletableFuture<Verdict> verdict = checkAsync(Map.of("to", "a@b.c", "body", longBody));

        UUID id = awaitRequest();
        PendingApprovalEntity row = saved.get();
        assertThat(row.getApprovalChannel()).isEqualTo("chat");
        assertThat(row.getSessionId()).isEqualTo(SESSION);
        assertThat(row.getToolName()).isEqualTo(TOOL);
        assertThat(row.getRequestedBy()).isEqualTo("user-7");
        assertThat(row.getRequestMessage()).isEqualTo("메일 발송 전 확인");
        assertThat(((String) row.getToolInput().get("body")).length()).isEqualTo(ToolApprovalGate.INPUT_VALUE_MAX_CHARS);
        assertThat(row.getTimeoutAt()).isEqualTo(row.getRequestedAt().plusSeconds(120));

        StreamEvent.ApprovalRequired req = (StreamEvent.ApprovalRequired) events.get(0);
        assertThat(req.toolUseId()).isEqualTo("tu-1");
        assertThat(req.timeoutSeconds()).isEqualTo(120);
        assertThat(req.message()).isEqualTo("메일 발송 전 확인");

        assertThat(turns.resolve(id, ChatTurnRegistry.APPROVED)).isTrue();
        assertThat(verdict.get(2, TimeUnit.SECONDS).proceed()).isTrue();
        assertThat(resolved().status()).isEqualTo("approved");
    }

    @Test
    void TC_CHT_113_거절하면_실행하지_않고_AI_에_거절을_알린다() throws Exception {
        requireApproval();
        openTurn("user-7");
        CompletableFuture<Verdict> verdict = checkAsync(Map.of());
        UUID id = awaitRequest();
        turns.resolve(id, ChatTurnRegistry.REJECTED);

        Verdict v = verdict.get(2, TimeUnit.SECONDS);
        assertThat(v.proceed()).isFalse();
        assertThat(v.refusal()).isEqualTo(ToolApprovalGate.MSG_REJECTED);
        assertThat(resolved().status()).isEqualTo("rejected");
    }

    @Test
    void TC_CHT_113_마감이_지나면_timeout_으로_닫는다() throws Exception {
        requireApproval();
        openTurn("user-7");
        when(approvals.closeIfPending(any(), eq("timeout"), isNull(), isNull(), any())).thenReturn(1);
        CompletableFuture<Verdict> verdict = checkAsync(Map.of());
        awaitRequest();
        clock.advanceSeconds(121);

        Verdict v = verdict.get(2, TimeUnit.SECONDS);
        assertThat(v.refusal()).isEqualTo(ToolApprovalGate.MSG_TIMEOUT);
        verify(approvals).closeIfPending(eq(saved.get().getId()), eq("timeout"), isNull(), isNull(), any());
        assertThat(resolved().status()).isEqualTo("timeout");
    }

    @Test
    void TC_CHT_113_마감과_같은_때_결정이_먼저_닫았으면_그_결정을_따른다() throws Exception {
        requireApproval();
        openTurn("user-7");
        when(approvals.closeIfPending(any(), eq("timeout"), isNull(), isNull(), any())).thenAnswer(inv -> {
            saved.get().setStatus("approved");
            return 0;
        });
        CompletableFuture<Verdict> verdict = checkAsync(Map.of());
        awaitRequest();
        clock.advanceSeconds(121);

        assertThat(verdict.get(2, TimeUnit.SECONDS).proceed()).isTrue();
    }

    @Test
    void TC_CHT_113_응답을_중지하면_cancelled_로_닫고_턴을_끝낸다() throws Exception {
        requireApproval();
        ChatTurn turn = openTurn("user-7");
        when(approvals.closeIfPending(any(), eq("cancelled"), isNull(), isNull(), any())).thenReturn(1);
        CompletableFuture<Verdict> verdict = checkAsync(Map.of());
        awaitRequest();
        cancellation.cancel(SESSION);

        Verdict v = verdict.get(2, TimeUnit.SECONDS);
        assertThat(v.refusal()).isEqualTo(ToolApprovalGate.MSG_CANCELLED);
        assertThat(turn.cancelled().get()).isTrue();
        verify(approvals).closeIfPending(eq(saved.get().getId()), eq("cancelled"), isNull(), isNull(), any());
    }

    @Test
    void TC_CHT_113_스트림이_끊기면_cancelled_로_닫고_턴을_끝낸다() throws Exception {
        requireApproval();
        AtomicBoolean token = cancellation.register(SESSION);
        turns.open(SESSION, "user-7", ev -> {
            events.add(ev);
            if (ev instanceof StreamEvent.KeepAlive k) k.lost().set(true);
        }, token);
        when(approvals.closeIfPending(any(), eq("cancelled"), isNull(), isNull(), any())).thenReturn(1);
        CompletableFuture<Verdict> verdict = checkAsync(Map.of());
        awaitRequest();
        clock.advanceSeconds(16);

        assertThat(verdict.get(2, TimeUnit.SECONDS).refusal()).isEqualTo(ToolApprovalGate.MSG_CANCELLED);
        assertThat(token.get()).isTrue();
    }

    @Test
    void 같은_세션에_새_턴이_열리면_앞_턴의_기다림은_중지로_끝난다() throws Exception {
        requireApproval();
        openTurn("user-7");
        when(approvals.closeIfPending(any(), eq("cancelled"), isNull(), isNull(), any())).thenReturn(1);
        CompletableFuture<Verdict> verdict = checkAsync(Map.of());
        awaitRequest();
        openTurn("user-7");

        assertThat(verdict.get(2, TimeUnit.SECONDS).refusal()).isEqualTo(ToolApprovalGate.MSG_CANCELLED);
    }

    @Test
    void 대기_설정값은_10_에서_240_사이로_쓴다() throws Exception {
        requireApproval();
        when(settings.getInt(eq(ToolApprovalGate.TIMEOUT_KEY), anyInt())).thenReturn(5000);
        openTurn("user-7");
        CompletableFuture<Verdict> verdict = checkAsync(Map.of());
        UUID id = awaitRequest();
        assertThat(((StreamEvent.ApprovalRequired) events.get(0)).timeoutSeconds()).isEqualTo(240);
        turns.resolve(id, ChatTurnRegistry.APPROVED);
        verdict.get(2, TimeUnit.SECONDS);
    }
}
