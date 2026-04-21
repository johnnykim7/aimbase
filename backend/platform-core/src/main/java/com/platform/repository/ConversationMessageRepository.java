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
}
