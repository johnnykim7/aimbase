package com.platform.session;

import com.platform.domain.ConversationMemoryEntity;
import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.UnifiedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 세션 메모리 기반 압축 서비스 (PRD-204, CR-030 Phase 5).
 *
 * 장기 기억(LONG_TERM)을 활용하여 오래된 대화 메시지를 대체한다.
 * LLM 호출 없이 저비용으로 컨텍스트를 압축하며,
 * AUTO_COMPACT 이전에 시도하여 비용을 절감한다.
 *
 * 실패(장기 기억 부족 등) 시 null 반환 → 호출자가 AUTO_COMPACT로 폴백.
 */
@Component
public class SessionMemoryCompactionService {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryCompactionService.class);
    private static final int ESTIMATED_TOKENS_PER_CHAR = 4;
    private static final int MIN_MEMORIES_REQUIRED = 2;

    private final MemoryService memoryService;

    public SessionMemoryCompactionService(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * 세션의 장기 기억을 활용하여 대화를 압축한다.
     *
     * 전략:
     * 1. LONG_TERM 메모리를 조회
     * 2. 메모리가 충분하면 오래된 대화 메시지를 메모리 요약으로 대체
     * 3. 최근 대화는 유지
     *
     * @param sessionId  세션 ID
     * @param userId     사용자 ID
     * @param messages   현재 대화 메시지
     * @param maxTokens  토큰 한도
     * @return 압축된 메시지 목록, 실패 시 null
     */
    public List<UnifiedMessage> compact(String sessionId, String userId,
                                        List<UnifiedMessage> messages, int maxTokens) {
        // 1. LONG_TERM 메모리 조회
        List<ConversationMemoryEntity> memories =
                memoryService.getBySessionAndLayer(sessionId, MemoryLayer.LONG_TERM);

        if (memories.size() < MIN_MEMORIES_REQUIRED) {
            log.debug("SESSION_MEMORY 압축 스킵: 장기 기억 부족 ({}건 < {}건)",
                    memories.size(), MIN_MEMORIES_REQUIRED);
            return null;
        }

        // 2. SYSTEM 메시지 분리
        List<UnifiedMessage> systemMessages = messages.stream()
                .filter(m -> m.role() == UnifiedMessage.Role.SYSTEM)
                .toList();
        List<UnifiedMessage> otherMessages = messages.stream()
                .filter(m -> m.role() != UnifiedMessage.Role.SYSTEM)
                .toList();

        if (otherMessages.size() <= 2) {
            return null; // 대화가 너무 짧아 압축 불필요
        }

        // 3. 장기 기억을 "[세션 메모리 컨텍스트]"로 구성
        String memoryContext = memories.stream()
                .map(ConversationMemoryEntity::getContent)
                .collect(Collectors.joining("\n• ", "• ", ""));

        UnifiedMessage memorySummary = UnifiedMessage.ofText(
                UnifiedMessage.Role.SYSTEM,
                "[세션 메모리 컨텍스트]\n" + memoryContext
        );

        // 4. 최근 대화 유지 비율 결정 (최소 1/3 보존)
        int keepCount = Math.max(2, otherMessages.size() / 3);
        List<UnifiedMessage> recentMessages = otherMessages.subList(
                otherMessages.size() - keepCount, otherMessages.size());

        // 5. 결과 조립: system + memory summary + recent
        List<UnifiedMessage> result = new ArrayList<>(systemMessages);
        result.add(memorySummary);
        result.addAll(recentMessages);

        int resultTokens = estimateTokens(result);
        if (resultTokens > maxTokens) {
            log.debug("SESSION_MEMORY 압축 후에도 토큰 초과: {} > {}", resultTokens, maxTokens);
            return null; // 여전히 초과 → AUTO_COMPACT로 폴백
        }

        int originalTokens = estimateTokens(messages);
        log.info("SESSION_MEMORY 압축 적용: {}개 메시지 → 메모리 요약 + {}개 유지 (토큰: {} → {})",
                otherMessages.size() - keepCount, keepCount, originalTokens, resultTokens);
        return result;
    }

    private int estimateTokens(List<UnifiedMessage> messages) {
        return messages.stream()
                .mapToInt(msg -> msg.content().stream()
                        .mapToInt(block -> {
                            if (block instanceof ContentBlock.Text t) {
                                return t.text().length() / ESTIMATED_TOKENS_PER_CHAR + 4;
                            }
                            return 10;
                        }).sum())
                .sum();
    }
}
