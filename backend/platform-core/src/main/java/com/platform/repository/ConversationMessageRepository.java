package com.platform.repository;

import com.platform.domain.ConversationMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessageEntity, UUID> {

    // CR-046: 활성 메시지만 노출.
    @Query("SELECT m FROM ConversationMessageEntity m WHERE m.sessionId = :sessionId AND m.deletedAt IS NULL ORDER BY m.createdAt ASC")
    List<ConversationMessageEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    long countBySessionId(String sessionId);

    @Modifying
    @Transactional
    void deleteBySessionId(String sessionId);

    /** CR-046: Soft delete (UPDATE deleted_at). 부모 세션 삭제 시 cascade로 호출. */
    @Modifying
    @Transactional
    @Query("UPDATE ConversationMessageEntity m SET m.deletedAt = :ts WHERE m.sessionId = :sessionId AND m.deletedAt IS NULL")
    int softDeleteBySessionId(String sessionId, OffsetDateTime ts);

    /** CR-049 PRD-303: 가장 최근 COMPACT_BOUNDARY 메시지. Pageable(size=1) 로 호출. */
    @Query("SELECT m FROM ConversationMessageEntity m " +
           "WHERE m.sessionId = :sessionId AND m.messageType = 'COMPACT_BOUNDARY' AND m.deletedAt IS NULL " +
           "ORDER BY m.createdAt DESC")
    List<ConversationMessageEntity> findLatestBoundary(String sessionId,
                                                        org.springframework.data.domain.Pageable pageable);

    /** CR-049 PRD-303: 지정 시각(=boundary 생성 시각) 이후 활성 메시지만 반환. */
    @Query("SELECT m FROM ConversationMessageEntity m " +
           "WHERE m.sessionId = :sessionId AND m.deletedAt IS NULL AND m.createdAt >= :since " +
           "ORDER BY m.createdAt ASC")
    List<ConversationMessageEntity> findBySessionIdSince(String sessionId, OffsetDateTime since);
}
