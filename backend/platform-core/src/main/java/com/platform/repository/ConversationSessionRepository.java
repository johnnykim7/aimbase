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
}
