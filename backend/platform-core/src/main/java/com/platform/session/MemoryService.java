package com.platform.session;

import com.platform.domain.ConversationMemoryEntity;
import com.platform.llm.model.UnifiedMessage;
import com.platform.repository.ConversationMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 메모리 서비스 (PRD-130, PRD-202).
 *
 * 4계층 메모리를 관리하고, 대화 컨텍스트에 주입할 메시지를 빌드한다.
 * SYSTEM_RULES → LONG_TERM → USER_PROFILE → (SHORT_TERM은 기존 대화 메시지)
 *
 * PRD-202: scope 우선순위 병합 — PRIVATE > TEAM > GLOBAL.
 * 동일 memoryType+content 키 충돌 시 PRIVATE이 우선.
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final int LONG_TERM_TOP_K = 5;
    private static final int TEAM_LONG_TERM_TOP_K = 3;
    private static final int GLOBAL_LONG_TERM_TOP_K = 2;

    private final ConversationMemoryRepository memoryRepository;

    public MemoryService(ConversationMemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    /** 메모리 저장 (기존 호환: PRIVATE scope) */
    public ConversationMemoryEntity save(String sessionId, String userId,
                                          MemoryLayer layer, String content) {
        return save(sessionId, userId, layer, content, MemoryScope.PRIVATE, null);
    }

    /** 메모리 저장 (scope 지정) */
    public ConversationMemoryEntity save(String sessionId, String userId,
                                          MemoryLayer layer, String content,
                                          MemoryScope scope, String teamId) {
        ConversationMemoryEntity entity = new ConversationMemoryEntity();
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setMemoryType(layer.name());
        entity.setContent(content);
        entity.setScope(scope.name());
        entity.setTeamId(teamId);
        return memoryRepository.save(entity);
    }

    /** 계층별 메모리 조회 */
    public List<ConversationMemoryEntity> getBySessionAndLayer(String sessionId, MemoryLayer layer) {
        return memoryRepository.findBySessionIdAndMemoryTypeOrderByCreatedAtDesc(
                sessionId, layer.name());
    }

    /** PRIVATE scope — 세션+계층 조회 */
    public List<ConversationMemoryEntity> getPrivateMemory(String sessionId, MemoryLayer layer) {
        return memoryRepository.findBySessionIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
                sessionId, layer.name(), MemoryScope.PRIVATE.name());
    }

    /** GLOBAL scope — 계층별 조회 */
    public List<ConversationMemoryEntity> getGlobalMemory(MemoryLayer layer) {
        return memoryRepository.findByScopeAndMemoryTypeOrderByCreatedAtDesc(
                MemoryScope.GLOBAL.name(), layer.name());
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
    public void delete(UUID id) {
        memoryRepository.deleteById(id);
    }

    /** 세션 메모리 전체 삭제 */
    public void deleteBySession(String sessionId) {
        memoryRepository.deleteBySessionId(sessionId);
    }

    /**
     * 대화 컨텍스트에 주입할 메모리 메시지 목록을 빌드한다 (PRD-130).
     * 기존 호환: teamId 없이 호출 시 PRIVATE만 사용.
     */
    public List<UnifiedMessage> buildMemoryContext(String sessionId, String userId) {
        return buildMemoryContext(sessionId, userId, null);
    }

    /**
     * 대화 컨텍스트에 주입할 메모리 메시지 목록을 빌드한다 (PRD-202).
     *
     * 우선순위: PRIVATE > TEAM > GLOBAL.
     * 동일 content는 상위 scope가 우선 — 중복 주입 방지.
     */
    public List<UnifiedMessage> buildMemoryContext(String sessionId, String userId, String teamId) {
        List<UnifiedMessage> memoryMessages = new ArrayList<>();
        Set<String> seenContents = new HashSet<>();

        // ── 1. SYSTEM_RULES: PRIVATE → TEAM → GLOBAL ──
        List<ConversationMemoryEntity> privateRules =
                getPrivateMemory(sessionId, MemoryLayer.SYSTEM_RULES);
        for (ConversationMemoryEntity rule : privateRules) {
            seenContents.add(rule.getContent());
            memoryMessages.add(UnifiedMessage.ofText(
                    UnifiedMessage.Role.SYSTEM, "[규칙] " + rule.getContent()));
        }

        if (teamId != null) {
            List<ConversationMemoryEntity> teamRules = memoryRepository
                    .findByTeamIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
                            teamId, MemoryLayer.SYSTEM_RULES.name(), MemoryScope.TEAM.name());
            for (ConversationMemoryEntity rule : teamRules) {
                if (seenContents.add(rule.getContent())) {
                    memoryMessages.add(UnifiedMessage.ofText(
                            UnifiedMessage.Role.SYSTEM, "[팀 규칙] " + rule.getContent()));
                }
            }
        }

        List<ConversationMemoryEntity> globalRules = getGlobalMemory(MemoryLayer.SYSTEM_RULES);
        for (ConversationMemoryEntity rule : globalRules) {
            if (seenContents.add(rule.getContent())) {
                memoryMessages.add(UnifiedMessage.ofText(
                        UnifiedMessage.Role.SYSTEM, "[글로벌 규칙] " + rule.getContent()));
            }
        }

        // ── 2. LONG_TERM: PRIVATE(top-5) → TEAM(top-3) → GLOBAL(top-2) ──
        seenContents.clear();

        List<ConversationMemoryEntity> privateLT =
                getPrivateMemory(sessionId, MemoryLayer.LONG_TERM);
        privateLT.stream().limit(LONG_TERM_TOP_K).forEach(lt -> {
            seenContents.add(lt.getContent());
            memoryMessages.add(UnifiedMessage.ofText(
                    UnifiedMessage.Role.SYSTEM, "[장기 기억] " + lt.getContent()));
        });

        if (teamId != null) {
            List<ConversationMemoryEntity> teamLT = memoryRepository
                    .findByTeamIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
                            teamId, MemoryLayer.LONG_TERM.name(), MemoryScope.TEAM.name());
            teamLT.stream().limit(TEAM_LONG_TERM_TOP_K)
                    .filter(lt -> seenContents.add(lt.getContent()))
                    .forEach(lt -> memoryMessages.add(UnifiedMessage.ofText(
                            UnifiedMessage.Role.SYSTEM, "[팀 기억] " + lt.getContent())));
        }

        List<ConversationMemoryEntity> globalLT = getGlobalMemory(MemoryLayer.LONG_TERM);
        globalLT.stream().limit(GLOBAL_LONG_TERM_TOP_K)
                .filter(lt -> seenContents.add(lt.getContent()))
                .forEach(lt -> memoryMessages.add(UnifiedMessage.ofText(
                        UnifiedMessage.Role.SYSTEM, "[글로벌 기억] " + lt.getContent())));

        // ── 3. USER_PROFILE ──
        if (userId != null) {
            List<ConversationMemoryEntity> profiles = getUserProfile(userId);
            profiles.stream()
                    .limit(3)
                    .forEach(p -> memoryMessages.add(UnifiedMessage.ofText(
                            UnifiedMessage.Role.SYSTEM, "[사용자 프로필] " + p.getContent())));
        }

        log.debug("메모리 컨텍스트 빌드: sessionId={}, teamId={}, 주입 메시지 {}건",
                sessionId, teamId, memoryMessages.size());
        return memoryMessages;
    }
}
