package com.platform.repository;

import com.platform.domain.ConversationSessionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
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
    @Query(value = "INSERT INTO conversation_sessions (id, session_id, title, message_count, scope_type, created_at, updated_at) "
            + "VALUES (:id, :sessionId, :title, :messageCount, COALESCE(:scopeType, 'chat'), NOW(), NOW()) "
            + "ON CONFLICT (session_id) DO UPDATE SET "
            + "  message_count = EXCLUDED.message_count, "
            + "  updated_at = NOW()",
            nativeQuery = true)
    void upsertSession(UUID id, String sessionId, String title, int messageCount, String scopeType);
}
