package com.platform.repository;

import java.util.Collection;
import org.springframework.data.domain.Pageable;
import java.util.List;
import org.springframework.data.repository.query.Param;
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

    /** CR-204 BIZ-292: 대화 완전 삭제. 삭제 표시 여부와 관계없이 그 대화의 행을 모두 지운다. */
    @Modifying
    @Query("DELETE FROM ChatGeneratedImageEntity g WHERE g.sessionId = :sessionId")
    int purgeBySessionId(@Param("sessionId") String sessionId);

    /** CR-204: 삭제 표시 여부와 관계없이 대화의 그림 전부(파일 경로를 모으려고). */
    List<ChatGeneratedImageEntity> findBySessionId(String sessionId);

    /** CR-204 BIZ-295: 삭제 표시 뒤 보관 기간이 지난 그림. */
    List<ChatGeneratedImageEntity> findByDeletedAtBefore(OffsetDateTime cutoff, Pageable pageable);

    @Modifying
    @Query("DELETE FROM ChatGeneratedImageEntity g WHERE g.id IN :ids")
    int deleteByIds(@Param("ids") Collection<UUID> ids);
}
