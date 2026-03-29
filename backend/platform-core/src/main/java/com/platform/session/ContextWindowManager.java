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
    private static final double SUMMARY_TRIGGER_RATIO = 0.70;
    private static final double SUMMARY_TARGET_RATIO = 0.50;

    private final ConversationSummarizer summarizer;

    public ContextWindowManager(ConversationSummarizer summarizer) {
        this.summarizer = summarizer;
    }

    /**
     * 메시지를 트리밍한다. 70% 초과 시 요약을 시도하고,
     * 요약 실패 시 단순 삭제 방식으로 폴백.
     */
    public List<UnifiedMessage> trim(List<UnifiedMessage> messages, int maxTokens) {
        int total = estimateTokens(messages);
        if (total <= maxTokens) {
            return messages;
        }

        int triggerThreshold = (int) (maxTokens * SUMMARY_TRIGGER_RATIO);
        int targetTokens = (int) (maxTokens * SUMMARY_TARGET_RATIO);

        if (total > triggerThreshold) {
            List<UnifiedMessage> summarized = trySummarizeAndTrim(messages, targetTokens);
            if (summarized != null) {
                return summarized;
            }
        }

        // 요약 실패 시 단순 트리밍
        return simpleTrim(messages, maxTokens);
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
