package com.platform.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
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

    /** CR-204 BIZ-292: 대화 완전 삭제. 삭제 표시 여부와 관계없이 그 대화의 행을 모두 지운다. */
    @Modifying
    @Query("DELETE FROM ConversationMemoryEntity m WHERE m.sessionId = :sessionId")
    int purgeBySessionId(@Param("sessionId") String sessionId);

    /** CR-204 BIZ-293: 그 사람의 기억 전부(모든 종류와 범위). */
    @Modifying
    @Query("DELETE FROM ConversationMemoryEntity m WHERE m.userId = :userId")
    int purgeByUserId(@Param("userId") String userId);

    @Modifying
    @Query("DELETE FROM ConversationMemoryEntity m WHERE m.userId LIKE :pattern ESCAPE '!'")
    int purgeByUserIdPrefix(@Param("pattern") String pattern);

    @Query("SELECT DISTINCT m.userId FROM ConversationMemoryEntity m WHERE m.userId LIKE :pattern ESCAPE '!'")
    List<String> findUserIdsByPrefix(@Param("pattern") String pattern);
}
