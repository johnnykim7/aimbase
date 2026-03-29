package com.platform.session;

import com.platform.domain.ConversationMemoryEntity;
import com.platform.llm.model.UnifiedMessage;
import com.platform.repository.ConversationMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 메모리 서비스 (PRD-130).
 *
 * 4계층 메모리를 관리하고, 대화 컨텍스트에 주입할 메시지를 빌드한다.
 * SYSTEM_RULES → LONG_TERM → USER_PROFILE → (SHORT_TERM은 기존 대화 메시지)
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final int LONG_TERM_TOP_K = 5;

    private final ConversationMemoryRepository memoryRepository;

    public MemoryService(ConversationMemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    /** 메모리 저장 */
    public ConversationMemoryEntity save(String sessionId, String userId,
                                          MemoryLayer layer, String content) {
        ConversationMemoryEntity entity = new ConversationMemoryEntity();
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setMemoryType(layer.name());
        entity.setContent(content);
        return memoryRepository.save(entity);
    }

    /** 계층별 메모리 조회 */
    public List<ConversationMemoryEntity> getBySessionAndLayer(String sessionId, MemoryLayer layer) {
        return memoryRepository.findBySessionIdAndMemoryTypeOrderByCreatedAtDesc(
                sessionId, layer.name());
    }

    /** 사용자 프로필 메모리 조회 */
    public List<ConversationMemoryEntity> getUserProfile(String userId) {
        return memoryRepository.findByUserIdAndMemoryTypeOrderByCreatedAtDesc(
                userId, MemoryLayer.USER_PROFILE.name());
    }

    /** 세션의 전체 메모리 조회 */
    public List<ConversationMemoryEntity> getAllBySession(String sessionId) {
        return memoryRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
    }

    /** 메모리 삭제 */
    public void delete(java.util.UUID id) {
        memoryRepository.deleteById(id);
    }

    /** 세션 메모리 전체 삭제 */
    public void deleteBySession(String sessionId) {
        memoryRepository.deleteBySessionId(sessionId);
    }

    /**
     * 대화 컨텍스트에 주입할 메모리 메시지 목록을 빌드한다 (PRD-130).
     * SYSTEM_RULES + LONG_TERM(top-K) + USER_PROFILE을 SYSTEM 메시지로 주입.
     */
    public List<UnifiedMessage> buildMemoryContext(String sessionId, String userId) {
        List<UnifiedMessage> memoryMessages = new ArrayList<>();

        // 1. SYSTEM_RULES — 항상 주입
        List<ConversationMemoryEntity> rules = getBySessionAndLayer(sessionId, MemoryLayer.SYSTEM_RULES);
        for (ConversationMemoryEntity rule : rules) {
            memoryMessages.add(UnifiedMessage.ofText(
                    UnifiedMessage.Role.SYSTEM, "[규칙] " + rule.getContent()));
        }

        // 2. LONG_TERM — relevance 기반 top-K
        List<ConversationMemoryEntity> longTerms = getBySessionAndLayer(sessionId, MemoryLayer.LONG_TERM);
        longTerms.stream()
                .limit(LONG_TERM_TOP_K)
                .forEach(lt -> memoryMessages.add(UnifiedMessage.ofText(
                        UnifiedMessage.Role.SYSTEM, "[장기 기억] " + lt.getContent())));

        // 3. USER_PROFILE
        if (userId != null) {
            List<ConversationMemoryEntity> profiles = getUserProfile(userId);
            profiles.stream()
                    .limit(3)
                    .forEach(p -> memoryMessages.add(UnifiedMessage.ofText(
                            UnifiedMessage.Role.SYSTEM, "[사용자 프로필] " + p.getContent())));
        }

        log.debug("메모리 컨텍스트 빌드: sessionId={}, 주입 메시지 {}건",
                sessionId, memoryMessages.size());
        return memoryMessages;
    }
}
