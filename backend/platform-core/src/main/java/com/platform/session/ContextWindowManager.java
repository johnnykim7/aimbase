package com.platform.session;

import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 토큰 윈도우 관리 (PRD-127 확장).
 *
 * 메시지 목록이 최대 토큰의 70%에 도달하면 ConversationSummarizer를 트리거하여
 * 오래된 메시지를 요약으로 교체한다. 요약본은 SYSTEM 메시지 바로 뒤에
 * "[이전 대화 요약]" 형태로 주입.
 */
@Component
public class ContextWindowManager {

    private static final Logger log = LoggerFactory.getLogger(ContextWindowManager.class);

    private static final int DEFAULT_MAX_TOKENS = 100_000;
    private static final int ESTIMATED_TOKENS_PER_CHAR = 4;

    // B6: 3단계 graduated 압축 임계값
    private static final double SNIP_TRIGGER_RATIO       = 0.70; // Level 1: 메시지 3개 제거 (무비용)
    private static final double MICRO_TRIGGER_RATIO      = 0.85; // Level 2: 최하위 1/3 → 200자 (Haiku)
    private static final double AUTO_TRIGGER_RATIO       = 0.93; // Level 3: 전체 요약 → 500자 (Haiku)
    private static final double SUMMARY_TARGET_RATIO     = 0.50;

    private final ConversationSummarizer summarizer;

    public ContextWindowManager(ConversationSummarizer summarizer) {
        this.summarizer = summarizer;
    }

    /**
     * B6: 3단계 graduated 압축.
     *
     * <pre>
     * < 70%  : 그대로 반환
     * 70~85% : Level 1 — snip (오래된 메시지 3개 제거)
     * 85~93% : Level 2 — microcompact (최하위 1/3 → 200자 불릿 요약)
     * ≥ 93%  : Level 3 — autocompact (전체 요약 → 500자)
     * </pre>
     *
     * 각 단계 실패 시 상위 단계로 에스컬레이션, 최종 폴백은 simpleTrim.
     */
    public List<UnifiedMessage> trim(List<UnifiedMessage> messages, int maxTokens) {
        int total = estimateTokens(messages);
        int snipThreshold  = (int) (maxTokens * SNIP_TRIGGER_RATIO);
        int microThreshold = (int) (maxTokens * MICRO_TRIGGER_RATIO);
        int autoThreshold  = (int) (maxTokens * AUTO_TRIGGER_RATIO);
        int targetTokens   = (int) (maxTokens * SUMMARY_TARGET_RATIO);

        if (total <= snipThreshold) {
            return messages;  // 여유 있음
        }

        if (total <= microThreshold) {
            // Level 1: snip — 빠르고 비용 없음
            log.debug("B6 snip 적용: {}토큰 > {}% 임계값", total, (int)(SNIP_TRIGGER_RATIO*100));
            return snip(messages, 3);
        }

        if (total <= autoThreshold) {
            // Level 2: microcompact — Haiku로 짧은 요약
            log.debug("B6 microcompact 적용: {}토큰 > {}% 임계값", total, (int)(MICRO_TRIGGER_RATIO*100));
            List<UnifiedMessage> micro = tryMicroCompact(messages, targetTokens);
            if (micro != null) return micro;
            // 실패 시 snip으로 폴백
            return snip(messages, 5);
        }

        // Level 3: autocompact — full 요약
        log.debug("B6 autocompact 적용: {}토큰 > {}% 임계값", total, (int)(AUTO_TRIGGER_RATIO*100));
        List<UnifiedMessage> summarized = trySummarizeAndTrim(messages, targetTokens);
        if (summarized != null) return summarized;

        return simpleTrim(messages, maxTokens);  // 최종 폴백
    }

    public List<UnifiedMessage> trim(List<UnifiedMessage> messages) {
        return trim(messages, DEFAULT_MAX_TOKENS);
    }

    /**
     * 요약 + 트리밍 (PRD-127).
     * 오래된 메시지를 요약으로 교체하고, 최근 메시지만 유지.
     */
    private List<UnifiedMessage> trySummarizeAndTrim(List<UnifiedMessage> messages, int targetTokens) {
        List<UnifiedMessage> systemMessages = messages.stream()
                .filter(m -> m.role() == UnifiedMessage.Role.SYSTEM)
                .toList();
        List<UnifiedMessage> otherMessages = new ArrayList<>(messages.stream()
                .filter(m -> m.role() != UnifiedMessage.Role.SYSTEM)
                .toList());

        if (otherMessages.size() <= 2) {
            return null; // 요약할 만큼 충분하지 않음
        }

        // 전반부를 요약 대상으로, 후반부를 유지 대상으로 분리
        int keepCount = Math.max(2, otherMessages.size() / 3);
        List<UnifiedMessage> toSummarize = otherMessages.subList(0, otherMessages.size() - keepCount);
        List<UnifiedMessage> toKeep = otherMessages.subList(otherMessages.size() - keepCount, otherMessages.size());

        String summaryText = summarizer.summarize(toSummarize);
        if (summaryText == null || summaryText.isBlank()) {
            return null; // 요약 실패 → 폴백
        }

        // [이전 대화 요약] 메시지 생성
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

        // 목표 이하로 줄었으면 반환, 아니면 추가 트리밍
        if (resultTokens <= targetTokens) {
            return result;
        }
        return simpleTrim(result, targetTokens);
    }

    /**
     * B6 Level 1 — snip: SYSTEM 유지, 오래된 비-SYSTEM 메시지 n개 제거.
     * LLM 호출 없이 즉시 처리.
     */
    private List<UnifiedMessage> snip(List<UnifiedMessage> messages, int removeCount) {
        List<UnifiedMessage> system = messages.stream()
                .filter(m -> m.role() == UnifiedMessage.Role.SYSTEM)
                .toList();
        List<UnifiedMessage> others = new ArrayList<>(messages.stream()
                .filter(m -> m.role() != UnifiedMessage.Role.SYSTEM)
                .toList());

        int toRemove = Math.min(removeCount, Math.max(0, others.size() - 2)); // 최소 2개 보존
        for (int i = 0; i < toRemove; i++) {
            others.remove(0);
        }

        List<UnifiedMessage> result = new ArrayList<>(system);
        result.addAll(others);
        log.debug("B6 snip: {}개 메시지 제거", toRemove);
        return result;
    }

    /**
     * B6 Level 2 — microcompact: 오래된 1/3 메시지를 Haiku로 짧게 요약.
     * 실패 시 null 반환 → 호출자가 snip으로 폴백.
     */
    private List<UnifiedMessage> tryMicroCompact(List<UnifiedMessage> messages, int targetTokens) {
        List<UnifiedMessage> system = messages.stream()
                .filter(m -> m.role() == UnifiedMessage.Role.SYSTEM)
                .toList();
        List<UnifiedMessage> others = new ArrayList<>(messages.stream()
                .filter(m -> m.role() != UnifiedMessage.Role.SYSTEM)
                .toList());

        if (others.size() <= 4) return null; // 너무 짧아 요약 불필요

        int compactCount = others.size() / 3;
        List<UnifiedMessage> toCompact = others.subList(0, compactCount);
        List<UnifiedMessage> toKeep   = others.subList(compactCount, others.size());

        String micro = summarizer.microSummarize(toCompact);
        if (micro == null || micro.isBlank()) return null;

        List<UnifiedMessage> result = new ArrayList<>(system);
        result.add(UnifiedMessage.ofText(UnifiedMessage.Role.SYSTEM,
                "[이전 대화 압축 요약]\n" + micro));
        result.addAll(toKeep);

        log.info("B6 microcompact: {}개 → 요약 + {}개 유지 (토큰: {} → {})",
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
}
