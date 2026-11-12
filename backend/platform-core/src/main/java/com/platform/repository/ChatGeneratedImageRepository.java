package com.platform.repository;

import com.platform.domain.ChatGeneratedImageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatGeneratedImageRepository extends JpaRepository<ChatGeneratedImageEntity, UUID> {

    Optional<ChatGeneratedImageEntity> findByIdAndSessionIdAndDeletedAtIsNull(UUID id, String sessionId);

    /** CR-184 BIZ-231: 대화를 지우면 그 대화의 그림도 지운 표시. */
    @Modifying
    @Transactional
    @Query("UPDATE ChatGeneratedImageEntity i SET i.deletedAt = :ts WHERE i.sessionId = :sessionId AND i.deletedAt IS NULL")
    int softDeleteBySessionId(String sessionId, OffsetDateTime ts);

    /**
     * CR-184 BIZ-231: 되감기로 묶음 메시지가 모두 지워진 그림에 지운 표시. 그림은 답 묶음(turn_id)으로 메시지와 이어진다.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE chat_generated_images i SET deleted_at = :ts "
            + "WHERE i.session_id = :sessionId AND i.deleted_at IS NULL AND i.turn_id IS NOT NULL "
            + "AND NOT EXISTS (SELECT 1 FROM conversation_messages m WHERE m.session_id = i.session_id "
            + "AND m.turn_id = i.turn_id AND m.deleted_at IS NULL)", nativeQuery = true)
    int softDeleteOrphanedBySessionId(String sessionId, OffsetDateTime ts);
}
