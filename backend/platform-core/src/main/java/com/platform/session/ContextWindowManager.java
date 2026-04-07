package com.platform.session;

import com.platform.hook.HookDispatcher;
import com.platform.hook.HookEvent;
import com.platform.hook.HookInput;
import com.platform.hook.HookOutput;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 토큰 윈도우 관리 (PRD-127, PRD-205, CR-030 Phase 5).
 *
 * 5단계 graduated 압축 전략:
 * <pre>
 * &lt; snip(70%)          : 그대로 반환
 * snip ~ micro(85%)    : Level 1 — SNIP (오래된 메시지 3개 제거)
 * micro ~ session(91%) : Level 2 — MICRO_COMPACT (최하위 1/3 → 200자 불릿 요약)
 * session ~ auto(93%)  : Level 3 — SESSION_MEMORY (장기 기억으로 대화 대체)
 * auto ~ block(98%)    : Level 4 — AUTO_COMPACT (전체 요약 → 500자)
 * ≥ block(98%)         : Level 5 — BLOCK → BlockingLimitException (429)
 * </pre>
 *
 * effectiveContextWindow = modelContextWindow - outputTokenReserve(20K)
 */
@Component
public class ContextWindowManager {

    private static final Logger log = LoggerFactory.getLogger(ContextWindowManager.class);

    private static final int DEFAULT_MAX_TOKENS = 100_000;
    private static final int OUTPUT_TOKEN_RESERVE = 20_000;
    private static final int ESTIMATED_TOKENS_PER_CHAR = 4;
    private static final double SUMMARY_TARGET_RATIO = 0.50;

    private final ConversationSummarizer summarizer;
    private final HookDispatcher hookDispatcher;
    private final SessionMemoryCompactionService sessionMemoryCompaction;
    private final PostCompactRecoveryService postCompactRecovery;
    private final CompactionThresholds thresholds;

    // C2: 세션별 마지막 압축 요약 저장 (session_summary 소스에서 활용)
    private final Map<String, String> sessionSummaries = new ConcurrentHashMap<>();

    public ContextWindowManager(ConversationSummarizer summarizer,
                                HookDispatcher hookDispatcher,
                                SessionMemoryCompactionService sessionMemoryCompaction,
                                PostCompactRecoveryService postCompactRecovery,
                                CompactionThresholds thresholds) {
        this.summarizer = summarizer;
        this.hookDispatcher = hookDispatcher;
        this.sessionMemoryCompaction = sessionMemoryCompaction;
        this.postCompactRecovery = postCompactRecovery;
        this.thresholds = thresholds;
        log.info("ContextWindowManager 초기화: thresholds={}", thresholds);
    }

    /** C2: 세션 압축 요약 조회. 압축이 한 번도 안 됐으면 null. */
    public String getSessionSummary(String sessionId) {
        if (sessionId == null) return null;
        return sessionSummaries.get(sessionId);
    }

    /** C2: 세션 요약 삭제 (세션 종료 시). */
    public void clearSessionSummary(String sessionId) {
        if (sessionId != null) sessionSummaries.remove(sessionId);
    }

    /**
     * PRD-205: 5단계 graduated 압축 + TrimResult 반환.
     *
     * @param messages       현재 메시지
     * @param maxTokens      모델 컨텍스트 윈도우 (output reserve 포함)
     * @param sessionId      세션 ID (nullable)
     * @param userId         사용자 ID (nullable, SESSION_MEMORY용)
     * @return TrimResult (압축 결과 + CompactionState)
     */
    public TrimResult trimWithState(List<UnifiedMessage> messages, int maxTokens,
                                    String sessionId, String userId) {
        int effectiveWindow = maxTokens - OUTPUT_TOKEN_RESERVE;
        int total = estimateTokens(messages);
        double usageRatio = (double) total / effectiveWindow;

        int snipThreshold = (int) (effectiveWindow * thresholds.snipRatio());
        int microThreshold = (int) (effectiveWindow * thresholds.microCompactRatio());
        int sessionMemoryThreshold = (int) (effectiveWindow * thresholds.sessionMemoryRatio());
        int autoThreshold = (int) (effectiveWindow * thresholds.autoCompactRatio());
        int targetTokens = (int) (effectiveWindow * SUMMARY_TARGET_RATIO);

        // Level 5: BLOCK — 차단 한계 도달
        if (usageRatio >= thresholds.blockingLimitRatio()) {
            CompactionState state = CompactionState.evaluate(usageRatio, thresholds, CompactionStrategy.BLOCK);
            throw new BlockingLimitException(usageRatio);
        }

        // 여유 있음 — 압축 불필요
        if (total <= snipThreshold) {
            return new TrimResult(messages, CompactionState.normal(usageRatio));
        }

        // CR-030 PRD-195: PreCompact 훅
        HookOutput preHook = hookDispatcher.dispatch(HookEvent.PRE_COMPACT,
                HookInput.of(HookEvent.PRE_COMPACT, null,
                        Map.of("messageCount", messages.size(),
                                "estimatedTokens", total,
                                "maxTokens", effectiveWindow,
                                "usageRatio", usageRatio),
                        Map.of()));
        if (preHook.decision() == com.platform.hook.HookDecision.BLOCK) {
            log.info("Compact blocked by hook, returning original messages");
            return new TrimResult(messages, CompactionState.normal(usageRatio));
        }

        List<UnifiedMessage> result;
        CompactionStrategy strategyUsed;

        if (total <= microThreshold) {
            // Level 1: SNIP — 빠르고 비용 없음
            log.debug("SNIP 적용: {}토큰 (사용률 {}%)", total, String.format("%.1f", usageRatio * 100));
            result = snip(messages, 3);
            strategyUsed = CompactionStrategy.SNIP;

        } else if (total <= sessionMemoryThreshold) {
            // Level 2: MICRO_COMPACT — Haiku로 짧은 요약
            log.debug("MICRO_COMPACT 적용: {}토큰 (사용률 {}%)", total, String.format("%.1f", usageRatio * 100));
            List<UnifiedMessage> micro = tryMicroCompact(messages, targetTokens);
            result = micro != null ? micro : snip(messages, 5);
            strategyUsed = CompactionStrategy.MICRO_COMPACT;

        } else if (total <= autoThreshold) {
            // Level 3: SESSION_MEMORY — 장기 기억으로 대화 대체 (LLM 무비용)
            log.debug("SESSION_MEMORY 시도: {}토큰 (사용률 {}%)", total, String.format("%.1f", usageRatio * 100));
            List<UnifiedMessage> memoryResult = (sessionId != null && userId != null)
                    ? sessionMemoryCompaction.compact(sessionId, userId, messages, effectiveWindow)
                    : null;

            if (memoryResult != null) {
                result = memoryResult;
                strategyUsed = CompactionStrategy.SESSION_MEMORY;
            } else {
                // SESSION_MEMORY 실패 → MICRO_COMPACT 폴백
                log.debug("SESSION_MEMORY 실패, MICRO_COMPACT로 폴백");
                List<UnifiedMessage> micro = tryMicroCompact(messages, targetTokens);
                result = micro != null ? micro : snip(messages, 5);
                strategyUsed = CompactionStrategy.MICRO_COMPACT;
            }

        } else {
            // Level 4: AUTO_COMPACT — full 요약
            log.debug("AUTO_COMPACT 적용: {}토큰 (사용률 {}%)", total, String.format("%.1f", usageRatio * 100));

            // SESSION_MEMORY 먼저 시도 (비용 절감)
            List<UnifiedMessage> memoryResult = (sessionId != null && userId != null)
                    ? sessionMemoryCompaction.compact(sessionId, userId, messages, effectiveWindow)
                    : null;

            if (memoryResult != null) {
                result = memoryResult;
                strategyUsed = CompactionStrategy.SESSION_MEMORY;
            } else {
                List<UnifiedMessage> summarized = trySummarizeAndTrim(messages, targetTokens);
                result = summarized != null ? summarized : simpleTrim(messages, effectiveWindow);
                strategyUsed = CompactionStrategy.AUTO_COMPACT;
            }
        }

        // CR-031 PRD-211: Post-Compact Recovery — Level 2~4 압축 후 컨텍스트 복구
        if (strategyUsed != CompactionStrategy.SNIP) {
            try {
                PostCompactRecoveryService.RecoveryResult recovery =
                        postCompactRecovery.recover(sessionId, userId, result, strategyUsed);
                result = recovery.messages();
                log.info("Post-Compact Recovery 적용: 파일 {}개, 메모리 {}개, 토큰 {}",
                        recovery.restoredFileCount(), recovery.restoredMemoryCount(),
                        recovery.totalTokensRestored());
            } catch (Exception e) {
                log.warn("Post-Compact Recovery 실패 (무시): {}", e.getMessage());
            }
        }

        // C2: sessionSummaries 갱신
        if (sessionId != null) {
            extractSummary(result).ifPresent(summary -> sessionSummaries.put(sessionId, summary));
        }

        CompactionState state = CompactionState.evaluate(usageRatio, thresholds, strategyUsed);

        // CR-030 PRD-195: PostCompact 훅
        hookDispatcher.dispatch(HookEvent.POST_COMPACT,
                HookInput.of(HookEvent.POST_COMPACT, null,
                        Map.of("originalCount", messages.size(),
                                "resultCount", result.size(),
                                "originalTokens", total,
                                "resultTokens", estimateTokens(result),
                                "strategy", strategyUsed.name(),
                                "usageRatio", usageRatio),
                        Map.of()));

        return new TrimResult(result, state);
    }

    /** 기존 호환: trim(messages, maxTokens) */
    public List<UnifiedMessage> trim(List<UnifiedMessage> messages, int maxTokens) {
        return trimWithState(messages, maxTokens, null, null).messages();
    }

    public List<UnifiedMessage> trim(List<UnifiedMessage> messages) {
        return trim(messages, DEFAULT_MAX_TOKENS);
    }

    /**
     * C2: sessionId 포함 trim — 압축 요약 생성 시 sessionSummaries에 저장.
     */
    public List<UnifiedMessage> trim(List<UnifiedMessage> messages, int maxTokens, String sessionId) {
        return trimWithState(messages, maxTokens, sessionId, null).messages();
    }

    /**
     * 요약 + 트리밍 (PRD-127).
     */
    private List<UnifiedMessage> trySummarizeAndTrim(List<UnifiedMessage> messages, int targetTokens) {
        List<UnifiedMessage> systemMessages = messages.stream()
                .filter(m -> m.role() == UnifiedMessage.Role.SYSTEM)
                .toList();
        List<UnifiedMessage> otherMessages = new ArrayList<>(messages.stream()
                .filter(m -> m.role() != UnifiedMessage.Role.SYSTEM)
                .toList());

        if (otherMessages.size() <= 2) {
            return null;
        }

        int keepCount = Math.max(2, otherMessages.size() / 3);
        List<UnifiedMessage> toSummarize = otherMessages.subList(0, otherMessages.size() - keepCount);
        List<UnifiedMessage> toKeep = otherMessages.subList(otherMessages.size() - keepCount, otherMessages.size());

        String summaryText = summarizer.summarize(toSummarize);
        if (summaryText == null || summaryText.isBlank()) {
            return null;
        }

        UnifiedMessage summaryMessage = UnifiedMessage.ofText(
                UnifiedMessage.Role.SYSTEM,
                "[이전 대화 요약]\n" + summaryText
        );

        List<UnifiedMessage> result = new ArrayList<>(systemMessages);
        result.add(summaryMessage);
        result.addAll(toKeep);

        int resultTokens = estimateTokens(result);
        log.info("대화 요약 적용: {}개 메시지 → 요약 + {}개 유지 (토큰: {} → {})",
                toSummarize.size(), toKeep.size(),
                estimateTokens(messages), resultTokens);

        if (resultTokens <= targetTokens) {
            return result;
        }
        return simpleTrim(result, targetTokens);
    }

    /** Level 1 — snip: SYSTEM 유지, 오래된 비-SYSTEM 메시지 n개 제거. */
    private List<UnifiedMessage> snip(List<UnifiedMessage> messages, int removeCount) {
        List<UnifiedMessage> system = messages.stream()
                .filter(m -> m.role() == UnifiedMessage.Role.SYSTEM)
                .toList();
        List<UnifiedMessage> others = new ArrayList<>(messages.stream()
                .filter(m -> m.role() != UnifiedMessage.Role.SYSTEM)
                .toList());

        int toRemove = Math.min(removeCount, Math.max(0, others.size() - 2));
        for (int i = 0; i < toRemove; i++) {
            others.remove(0);
        }

        List<UnifiedMessage> result = new ArrayList<>(system);
        result.addAll(others);
        log.debug("SNIP: {}개 메시지 제거", toRemove);
        return result;
    }

    /**
     * Level 2 — MICRO_COMPACT (PRD-212, CR-031): 0비용 마커 대체 우선.
     *
     * 1단계: 오래된 TOOL_RESULT 메시지를 마커("[Tool result cleared]")로 대체 (0비용).
     *        최근 3개 tool result는 보존.
     * 2단계: 마커 대체 후에도 토큰 초과 시 → Haiku microSummarize() 폴백.
     */
    private List<UnifiedMessage> tryMicroCompact(List<UnifiedMessage> messages, int targetTokens) {
        List<UnifiedMessage> system = messages.stream()
                .filter(m -> m.role() == UnifiedMessage.Role.SYSTEM)
                .toList();
        List<UnifiedMessage> others = new ArrayList<>(messages.stream()
                .filter(m -> m.role() != UnifiedMessage.Role.SYSTEM)
                .toList());

        if (others.size() <= 4) return null;

        // ── 1단계: 0비용 마커 대체 (PRD-212) ──
        int toolResultCount = 0;
        int totalToolResults = (int) others.stream()
                .filter(m -> m.role() == UnifiedMessage.Role.TOOL_RESULT)
                .count();
        int preserveCount = Math.min(3, totalToolResults);

        // 역순으로 세서 최근 3개 TOOL_RESULT 인덱스를 파악
        int preserved = 0;
        java.util.Set<Integer> preserveIndices = new java.util.HashSet<>();
        for (int i = others.size() - 1; i >= 0 && preserved < preserveCount; i--) {
            if (others.get(i).role() == UnifiedMessage.Role.TOOL_RESULT) {
                preserveIndices.add(i);
                preserved++;
            }
        }

        // 오래된 TOOL_RESULT → 마커 대체
        List<UnifiedMessage> markerReplaced = new ArrayList<>();
        int markersApplied = 0;
        for (int i = 0; i < others.size(); i++) {
            UnifiedMessage msg = others.get(i);
            if (msg.role() == UnifiedMessage.Role.TOOL_RESULT && !preserveIndices.contains(i)) {
                // 마커로 대체
                markerReplaced.add(UnifiedMessage.ofText(
                        UnifiedMessage.Role.TOOL_RESULT,
                        "[Tool result cleared]"));
                markersApplied++;
            } else {
                markerReplaced.add(msg);
            }
        }

        List<UnifiedMessage> markerResult = new ArrayList<>(system);
        markerResult.addAll(markerReplaced);

        if (markersApplied > 0) {
            int markerTokens = estimateTokens(markerResult);
            log.info("MICRO_COMPACT 마커 대체: {}개 tool result → 마커 (토큰: {} → {})",
                    markersApplied, estimateTokens(messages), markerTokens);

            if (markerTokens <= targetTokens * 1.3) {
                // 마커 대체로 충분히 줄어들었으면 LLM 호출 없이 반환
                return markerResult;
            }
        }

        // ── 2단계: Haiku microSummarize 폴백 ──
        int compactCount = others.size() / 3;
        List<UnifiedMessage> toCompact = others.subList(0, compactCount);
        List<UnifiedMessage> toKeep = others.subList(compactCount, others.size());

        String micro = summarizer.microSummarize(toCompact);
        if (micro == null || micro.isBlank()) {
            // Haiku도 실패하면 마커 결과라도 반환
            return markersApplied > 0 ? markerResult : null;
        }

        List<UnifiedMessage> result = new ArrayList<>(system);
        result.add(UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM,
                "[이전 대화 압축 요약]\n" + micro));
        result.addAll(toKeep);

        log.info("MICRO_COMPACT Haiku 폴백: {}개 → 요약 + {}개 유지 (토큰: {} → {})",
                toCompact.size(), toKeep.size(),
                estimateTokens(messages), estimateTokens(result));
        return result;
    }

    /** 단순 트리밍: SYSTEM 유지, 오래된 메시지부터 제거 */
    private List<UnifiedMessage> simpleTrim(List<UnifiedMessage> messages, int maxTokens) {
        List<UnifiedMessage> systemMessages = messages.stream()
                .filter(m -> m.role() == UnifiedMessage.Role.SYSTEM)
                .toList();
        List<UnifiedMessage> otherMessages = new ArrayList<>(messages.stream()
                .filter(m -> m.role() != UnifiedMessage.Role.SYSTEM)
                .toList());

        while (estimateTokens(systemMessages) + estimateTokens(otherMessages) > maxTokens
                && otherMessages.size() > 1) {
            otherMessages.remove(0);
        }

        List<UnifiedMessage> result = new ArrayList<>(systemMessages);
        result.addAll(otherMessages);
        return result;
    }

    /** C2: 결과에서 요약 텍스트 추출 */
    private java.util.Optional<String> extractSummary(List<UnifiedMessage> result) {
        return result.stream()
                .filter(m -> m.role() == UnifiedMessage.Role.SYSTEM)
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ContentBlock.Text t
                        && (t.text().startsWith("[이전 대화 압축 요약]")
                        || t.text().startsWith("[이전 대화 요약]")
                        || t.text().startsWith("[세션 메모리 컨텍스트]")))
                .map(b -> ((ContentBlock.Text) b).text())
                .findFirst();
    }

    public int estimateTokens(List<UnifiedMessage> messages) {
        return messages.stream()
                .mapToInt(this::estimateMessageTokens)
                .sum();
    }

    private int estimateMessageTokens(UnifiedMessage message) {
        return message.content().stream()
                .mapToInt(block -> {
                    if (block instanceof ContentBlock.Text t) {
                        return t.text().length() / ESTIMATED_TOKENS_PER_CHAR + 4;
                    }
                    return 10;
                })
                .sum();
    }

    /**
     * PRD-205: 압축 결과 (메시지 + 상태).
     */
    public record TrimResult(
            List<UnifiedMessage> messages,
            CompactionState state
    ) {}
}
