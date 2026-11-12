package com.platform.repository;

import com.platform.domain.ConversationMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationMemoryRepository extends JpaRepository<ConversationMemoryEntity, UUID> {

    List<ConversationMemoryEntity> findBySessionIdAndMemoryTypeOrderByCreatedAtDesc(
            String sessionId, String memoryType);

    List<ConversationMemoryEntity> findByUserIdAndMemoryTypeOrderByCreatedAtDesc(
            String userId, String memoryType);

    List<ConversationMemoryEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    void deleteBySessionId(String sessionId);

    // ── PRD-200: scope 기반 쿼리 ──

    /** PRIVATE scope: 세션+계층+scope 조회 */
    List<ConversationMemoryEntity> findBySessionIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
            String sessionId, String memoryType, String scope);

    /** TEAM scope: teamId+계층+scope 조회 */
    List<ConversationMemoryEntity> findByTeamIdAndMemoryTypeAndScopeOrderByCreatedAtDesc(
            String teamId, String memoryType, String scope);

    /** TEAM scope: teamId 전체 조회 */
    List<ConversationMemoryEntity> findByTeamIdAndScopeOrderByCreatedAtDesc(
            String teamId, String scope);

    /** scope별 전체 조회 (GLOBAL용) */
    List<ConversationMemoryEntity> findByScopeAndMemoryTypeOrderByCreatedAtDesc(
            String scope, String memoryType);

    /** scope별 전체 조회 */
    List<ConversationMemoryEntity> findByScopeOrderByCreatedAtDesc(String scope);

    // ── CR-179: 위젯 사용자 자동 기억 (user_id = 위젯 토큰 user_ref) ──

    List<ConversationMemoryEntity> findByUserIdAndMemoryTypeAndScopeOrderByCreatedAtAsc(
            String userId, String memoryType, String scope);

    Optional<ConversationMemoryEntity> findByIdAndUserIdAndMemoryTypeAndScope(
            UUID id, String userId, String memoryType, String scope);

    @Transactional
    long deleteByUserIdAndMemoryTypeAndScope(String userId, String memoryType, String scope);
}
