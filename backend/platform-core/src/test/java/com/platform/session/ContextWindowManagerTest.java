package com.platform.session;

import com.platform.hook.HookDecision;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookOutput;
import com.platform.llm.model.UnifiedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CR-030 Phase 5: ContextWindowManager 5전략 테스트 (PRD-203~206).
 */
@ExtendWith(MockitoExtension.class)
class ContextWindowManagerTest {

    @Mock private ConversationSummarizer summarizer;
    @Mock private HookDispatcher hookDispatcher;
    @Mock private SessionMemoryCompactionService sessionMemoryCompaction;
    @Mock private PostCompactRecoveryService postCompactRecovery;

    private ContextWindowManager manager;

    // 테스트용 임계값: snip=0.70, micro=0.85, sessionMemory=0.91, auto=0.93, block=0.98
    private static final CompactionThresholds THRESHOLDS = CompactionThresholds.DEFAULT;
    // effectiveWindow = 100_000 - 20_000 = 80_000
    private static final int MAX_TOKENS = 100_000;

    @BeforeEach
    void setUp() {
        manager = new ContextWindowManager(summarizer, hookDispatcher, sessionMemoryCompaction, postCompactRecovery, THRESHOLDS);

        // 기본: 훅 PASSTHROUGH
        lenient().when(hookDispatcher.dispatch(any(), any()))
                .thenReturn(HookOutput.PASSTHROUGH);
    }

    // ── PRD-205: 여유 있으면 압축 불필요 ──

    @Test
    void trimWithState_belowSnip_returnsOriginal() {
        List<UnifiedMessage> messages = makeMessages(5, 100); // ~630 tokens (매우 작음)
        var result = manager.trimWithState(messages, MAX_TOKENS, null, null);

        assertThat(result.messages()).isEqualTo(messages);
        assertThat(result.state().strategyUsed()).isNull();
        assertThat(result.state().isAboveWarning()).isFalse();
    }

    // ── PRD-205: Level 1 SNIP (70~85%) ──

    @Test
    void trimWithState_atSnipThreshold_appliesSnip() {
        // effectiveWindow = 80,000. snip threshold = 56,000.
        // 각 메시지 ~5,004 토큰 → 12개 = ~60,048 (> snip, < micro)
        List<UnifiedMessage> messages = makeMessages(12, 20_000);
        var result = manager.trimWithState(messages, MAX_TOKENS, null, null);

        assertThat(result.messages().size()).isLessThan(messages.size());
        assertThat(result.state().strategyUsed()).isEqualTo(CompactionStrategy.SNIP);
    }

    // ── PRD-205: Level 2 MICRO_COMPACT (85~91%) ──

    @Test
    void trimWithState_atMicroThreshold_appliesMicroCompact() {
        // micro threshold = 68,000. session memory threshold = 72,800.
        // 14개 * ~5,004 = ~70,056 (> micro, < sessionMemory)
        List<UnifiedMessage> messages = makeMessages(14, 20_000);
        when(summarizer.microSummarize(any())).thenReturn("• 요약1\n• 요약2");

        var result = manager.trimWithState(messages, MAX_TOKENS, null, null);

        assertThat(result.state().strategyUsed()).isEqualTo(CompactionStrategy.MICRO_COMPACT);
        verify(summarizer).microSummarize(any());
    }

    @Test
    void trimWithState_microFails_fallsBackToSnip() {
        List<UnifiedMessage> messages = makeMessages(14, 20_000);
        when(summarizer.microSummarize(any())).thenReturn(null);

        var result = manager.trimWithState(messages, MAX_TOKENS, null, null);

        // MICRO_COMPACT 실패 → snip 폴백이지만 전략은 MICRO_COMPACT으로 기록
        assertThat(result.state().strategyUsed()).isEqualTo(CompactionStrategy.MICRO_COMPACT);
        assertThat(result.messages().size()).isLessThan(messages.size());
    }

    // ── PRD-204/205: Level 3 SESSION_MEMORY (91~93%) ──

    @Test
    void trimWithState_atSessionMemoryThreshold_appliesSessionMemory() {
        // sessionMemory threshold = 72,800. auto threshold = 74,400.
        // 15개 * ~4,924 = ~73,860 (> sessionMemory, < auto, adjusted for system msg)
        List<UnifiedMessage> messages = makeMessagesForRatio(0.92, 80_000, 15);
        List<UnifiedMessage> compacted = makeMessages(5, 100);
        when(sessionMemoryCompaction.compact("sess-1", "user-1", messages, 80_000))
                .thenReturn(compacted);

        var result = manager.trimWithState(messages, MAX_TOKENS, "sess-1", "user-1");

        assertThat(result.state().strategyUsed()).isEqualTo(CompactionStrategy.SESSION_MEMORY);
        assertThat(result.messages()).isEqualTo(compacted);
    }

    @Test
    void trimWithState_sessionMemoryFails_fallsBackToMicro() {
        List<UnifiedMessage> messages = makeMessagesForRatio(0.92, 80_000, 15);
        when(sessionMemoryCompaction.compact(any(), any(), any(), anyInt())).thenReturn(null);
        when(summarizer.microSummarize(any())).thenReturn("• 폴백 요약");

        var result = manager.trimWithState(messages, MAX_TOKENS, "sess-1", "user-1");

        assertThat(result.state().strategyUsed()).isEqualTo(CompactionStrategy.MICRO_COMPACT);
    }

    // ── PRD-205: Level 4 AUTO_COMPACT (93~98%) ──

    @Test
    void trimWithState_atAutoThreshold_triesSessionMemoryFirst() {
        List<UnifiedMessage> messages = makeMessagesForRatio(0.95, 80_000, 15);
        List<UnifiedMessage> compacted = makeMessages(3, 100);
        when(sessionMemoryCompaction.compact("sess-1", "user-1", messages, 80_000))
                .thenReturn(compacted);

        var result = manager.trimWithState(messages, MAX_TOKENS, "sess-1", "user-1");

        // AUTO_COMPACT 영역이지만 SESSION_MEMORY가 성공하면 그것 사용
        assertThat(result.state().strategyUsed()).isEqualTo(CompactionStrategy.SESSION_MEMORY);
    }

    @Test
    void trimWithState_atAutoThreshold_sessionMemoryFails_appliesAutoCompact() {
        List<UnifiedMessage> messages = makeMessagesForRatio(0.95, 80_000, 15);
        when(sessionMemoryCompaction.compact(any(), any(), any(), anyInt())).thenReturn(null);
        when(summarizer.summarize(any())).thenReturn("전체 요약 텍스트");

        var result = manager.trimWithState(messages, MAX_TOKENS, "sess-1", "user-1");

        assertThat(result.state().strategyUsed()).isEqualTo(CompactionStrategy.AUTO_COMPACT);
        verify(summarizer).summarize(any());
    }

    // ── PRD-205: Level 5 BLOCK (≥98%) ──

    @Test
    void trimWithState_atBlockingLimit_throwsException() {
        List<UnifiedMessage> messages = makeMessagesForRatio(0.99, 80_000, 10);

        assertThatThrownBy(() -> manager.trimWithState(messages, MAX_TOKENS, null, null))
                .isInstanceOf(BlockingLimitException.class)
                .satisfies(ex -> {
                    BlockingLimitException ble = (BlockingLimitException) ex;
                    assertThat(ble.getUsageRatio()).isGreaterThanOrEqualTo(0.98);
                });
    }

    // ── PRD-203: CompactionState 판정 ──

    @Test
    void compactionState_evaluate_setsFlags() {
        var state = CompactionState.evaluate(0.96, THRESHOLDS, CompactionStrategy.AUTO_COMPACT);

        assertThat(state.isAboveWarning()).isTrue();       // 0.96 >= 0.85
        assertThat(state.isAboveError()).isTrue();          // 0.96 >= 0.93
        assertThat(state.isAboveAutoCompact()).isTrue();    // 0.96 >= 0.93
        assertThat(state.isAtBlockingLimit()).isFalse();    // 0.96 < 0.98
        assertThat(state.strategyUsed()).isEqualTo(CompactionStrategy.AUTO_COMPACT);
    }

    @Test
    void compactionState_normal_allFalse() {
        var state = CompactionState.normal(0.30);

        assertThat(state.isAboveWarning()).isFalse();
        assertThat(state.isAboveError()).isFalse();
        assertThat(state.isAtBlockingLimit()).isFalse();
        assertThat(state.strategyUsed()).isNull();
    }

    // ── PRD-203: CompactionThresholds 기본값 ──

    @Test
    void compactionThresholds_defaults() {
        assertThat(CompactionThresholds.DEFAULT.snipRatio()).isEqualTo(0.70);
        assertThat(CompactionThresholds.DEFAULT.microCompactRatio()).isEqualTo(0.85);
        assertThat(CompactionThresholds.DEFAULT.sessionMemoryRatio()).isEqualTo(0.91);
        assertThat(CompactionThresholds.DEFAULT.autoCompactRatio()).isEqualTo(0.93);
        assertThat(CompactionThresholds.DEFAULT.blockingLimitRatio()).isEqualTo(0.98);
    }

    // ── PRD-195: PreCompact 훅 BLOCK 시 압축 스킵 ──

    @Test
    void trimWithState_hookBlocks_returnsOriginal() {
        List<UnifiedMessage> messages = makeMessages(12, 20_000);
        when(hookDispatcher.dispatch(any(), any()))
                .thenReturn(new HookOutput(HookDecision.BLOCK, null, Map.of()));

        var result = manager.trimWithState(messages, MAX_TOKENS, null, null);

        assertThat(result.messages()).isEqualTo(messages);
    }

    // ── 기존 호환: trim(messages, maxTokens) ──

    @Test
    void trim_backwardsCompatible() {
        List<UnifiedMessage> messages = makeMessages(5, 100);
        List<UnifiedMessage> result = manager.trim(messages, MAX_TOKENS);

        assertThat(result).isEqualTo(messages);
    }

    // ── C2: sessionId trim → sessionSummaries 갱신 ──

    @Test
    void trim_withSessionId_storesSummary() {
        // AUTO_COMPACT 트리거되도록 큰 메시지 (userId=null이므로 SESSION_MEMORY 스킵)
        List<UnifiedMessage> messages = makeMessagesForRatio(0.95, 80_000, 15);
        lenient().when(sessionMemoryCompaction.compact(any(), any(), any(), anyInt())).thenReturn(null);
        when(summarizer.summarize(any())).thenReturn("요약 내용");

        manager.trim(messages, MAX_TOKENS, "sess-summary");

        String summary = manager.getSessionSummary("sess-summary");
        assertThat(summary).contains("이전 대화 요약");
    }

    // ── Helper ──

    private List<UnifiedMessage> makeMessages(int count, int charsPerMessage) {
        List<UnifiedMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String text = "x".repeat(charsPerMessage);
            messages.add(UnifiedMessage.ofText(
                    i == 0 ? UnifiedMessage.Role.SYSTEM : UnifiedMessage.Role.USER, text));
        }
        return messages;
    }

    /**
     * 특정 사용률을 만족하는 메시지 목록 생성.
     * estimateTokens = chars / 4 + 4 (per message)
     */
    private List<UnifiedMessage> makeMessagesForRatio(double targetRatio, int effectiveWindow, int count) {
        int targetTokens = (int) (effectiveWindow * targetRatio);
        int tokensPerMessage = targetTokens / count;
        int charsPerMessage = (tokensPerMessage - 4) * 4; // reverse: tokens = chars/4 + 4
        return makeMessages(count, Math.max(100, charsPerMessage));
    }
}
