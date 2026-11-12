package com.platform.repository;

import com.platform.domain.ConversationSessionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationSessionRepository extends JpaRepository<ConversationSessionEntity, UUID> {

    // CR-046: 모든 활성 조회는 deletedAt IS NULL 필터 적용.
    @Query("SELECT s FROM ConversationSessionEntity s WHERE s.sessionId = :sessionId AND s.deletedAt IS NULL")
    Optional<ConversationSessionEntity> findBySessionId(String sessionId);

    /** CR-046: 삭제 여부 무관 조회 (SessionStore 영속/관리/감사). */
    @Query("SELECT s FROM ConversationSessionEntity s WHERE s.sessionId = :sessionId")
    Optional<ConversationSessionEntity> findBySessionIdIncludingDeleted(String sessionId);

    @Query("SELECT s FROM ConversationSessionEntity s WHERE s.title LIKE %:query% AND s.deletedAt IS NULL ORDER BY s.updatedAt DESC")
    Page<ConversationSessionEntity> searchByTitle(String query, Pageable pageable);

    @Query("SELECT s FROM ConversationSessionEntity s WHERE s.deletedAt IS NULL ORDER BY s.updatedAt DESC")
    Page<ConversationSessionEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);

    /** CR-046: Hard delete. 일반 흐름에서는 ConversationController.delete가 soft delete(UPDATE)를 사용. */
    @Modifying
    @Transactional
    void deleteBySessionId(String sessionId);

    /**
     * CR-077 후속 (CR-083): conversation_sessions race-safe upsert.
     * SessionStore.persistToDb 비동기 VT 가 같은 session_id 로 동시 INSERT 하다 UNIQUE 위반.
     * Hibernate save() retry 로는 트랜잭션 경계 race 를 못 잡는 경우가 있어 native ON CONFLICT 로 정공.
     * id 는 신규 row 일 때만 사용; 이미 row 있으면 message_count / updated_at 만 갱신.
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO conversation_sessions (id, session_id, title, message_count, scope_type, user_id, created_at, updated_at) "
            + "VALUES (:id, :sessionId, :title, :messageCount, COALESCE(:scopeType, 'chat'), :userId, NOW(), NOW()) "
            + "ON CONFLICT (session_id) DO UPDATE SET "
            + "  message_count = EXCLUDED.message_count, "
            + "  user_id = COALESCE(conversation_sessions.user_id, EXCLUDED.user_id), "
            + "  updated_at = NOW()",
            nativeQuery = true)
    void upsertSession(UUID id, String sessionId, String title, int messageCount, String scopeType, String userId);

    /**
     * CR-178: 첫 질문으로 만든 세션을 위젯 프로젝트에 넣는다. 이미 프로젝트가 있으면 두지 않는다
     * (대화 요청의 프로젝트 칸은 세션을 처음 만들 때만 쓴다, PRD-424).
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE conversation_sessions SET chat_project_id = :chatProjectId "
            + "WHERE session_id = :sessionId AND chat_project_id IS NULL AND deleted_at IS NULL",
            nativeQuery = true)
    int assignChatProjectIfAbsent(@Param("sessionId") String sessionId, @Param("chatProjectId") UUID chatProjectId);

    /**
     * CR-178 BIZ-170: 위젯 대화 목록. 주인이 요청자이고 지우지 않았고 메시지가 있는 채팅 세션.
     *
     * @param projectFilter 0 이면 전부, 1 이면 그 프로젝트만, 2 이면 프로젝트에 없는 대화만
     * @param titlePattern  null 이면 제목을 보지 않는다. 주면 ILIKE 패턴(% 와 _ 는 호출자가 막는다)
     */
    @Query(value = "SELECT * FROM conversation_sessions s "
            + "WHERE s.user_id = :ownerRef AND s.deleted_at IS NULL AND s.scope_type = 'chat' AND s.message_count > 0 "
            + "AND (:projectFilter = 0 "
            + "     OR (:projectFilter = 1 AND s.chat_project_id = CAST(:chatProjectId AS UUID)) "
            + "     OR (:projectFilter = 2 AND s.chat_project_id IS NULL)) "
            + "AND (CAST(:titlePattern AS TEXT) IS NULL OR s.title ILIKE CAST(:titlePattern AS TEXT) ESCAPE '\\') "
            + "ORDER BY s.updated_at DESC",
            countQuery = "SELECT COUNT(*) FROM conversation_sessions s "
            + "WHERE s.user_id = :ownerRef AND s.deleted_at IS NULL AND s.scope_type = 'chat' AND s.message_count > 0 "
            + "AND (:projectFilter = 0 "
            + "     OR (:projectFilter = 1 AND s.chat_project_id = CAST(:chatProjectId AS UUID)) "
            + "     OR (:projectFilter = 2 AND s.chat_project_id IS NULL)) "
            + "AND (CAST(:titlePattern AS TEXT) IS NULL OR s.title ILIKE CAST(:titlePattern AS TEXT) ESCAPE '\\')",
            nativeQuery = true)
    Page<ConversationSessionEntity> findWidgetHistory(@Param("ownerRef") String ownerRef,
                                                      @Param("projectFilter") int projectFilter,
                                                      @Param("chatProjectId") String chatProjectId,
                                                      @Param("titlePattern") String titlePattern,
                                                      Pageable pageable);

    /** CR-178: 프로젝트 목록 행의 대화 수와 마지막 활동 시각. [chat_project_id, count, max(updated_at)] */
    @Query(value = "SELECT CAST(s.chat_project_id AS TEXT), COUNT(*), MAX(s.updated_at) FROM conversation_sessions s "
            + "WHERE s.user_id = :ownerRef AND s.deleted_at IS NULL AND s.scope_type = 'chat' AND s.message_count > 0 "
            + "AND s.chat_project_id IS NOT NULL GROUP BY s.chat_project_id",
            nativeQuery = true)
    java.util.List<Object[]> summarizeByChatProject(@Param("ownerRef") String ownerRef);
}
