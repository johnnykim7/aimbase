package com.platform.session;

import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 토큰 윈도우 관리.
 * 메시지 목록이 최대 토큰을 초과하면 오래된 메시지(system 제외)를 트리밍.
 */
@Component
public class ContextWindowManager {

    private static final int DEFAULT_MAX_TOKENS = 100_000;
    private static final int ESTIMATED_TOKENS_PER_CHAR = 4; // 대략적 추정

    public List<UnifiedMessage> trim(List<UnifiedMessage> messages, int maxTokens) {
        int total = estimateTokens(messages);
        if (total <= maxTokens) {
            return messages;
        }

        // SYSTEM 메시지는 항상 유지
        List<UnifiedMessage> systemMessages = messages.stream()
                .filter(m -> m.role() == UnifiedMessage.Role.SYSTEM)
                .toList();
        List<UnifiedMessage> otherMessages = new ArrayList<>(messages.stream()
                .filter(m -> m.role() != UnifiedMessage.Role.SYSTEM)
                .toList());

        // 오래된 메시지부터 제거 (앞에서부터)
        while (estimateTokens(systemMessages) + estimateTokens(otherMessages) > maxTokens
                && otherMessages.size() > 1) {
            otherMessages.remove(0);
        }

        List<UnifiedMessage> result = new ArrayList<>(systemMessages);
        result.addAll(otherMessages);
        return result;
    }

    public List<UnifiedMessage> trim(List<UnifiedMessage> messages) {
        return trim(messages, DEFAULT_MAX_TOKENS);
    }

    private int estimateTokens(List<UnifiedMessage> messages) {
        return messages.stream()
                .mapToInt(m -> estimateMessageTokens(m))
                .sum();
    }

    private int estimateMessageTokens(UnifiedMessage message) {
        return message.content().stream()
                .mapToInt(block -> {
                    if (block instanceof ContentBlock.Text t) {
                        return t.text().length() / ESTIMATED_TOKENS_PER_CHAR + 4;
                    }
                    return 10; // tool use/result
                })
                .sum();
    }
}
