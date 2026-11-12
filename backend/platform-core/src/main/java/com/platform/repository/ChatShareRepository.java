package com.platform.repository;

import com.platform.domain.ChatShareEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatShareRepository extends JpaRepository<ChatShareEntity, UUID> {

    Optional<ChatShareEntity> findByToken(String token);

    List<ChatShareEntity> findTop20BySessionIdAndCreatedByOrderByCreatedAtDesc(String sessionId, String createdBy);

    List<ChatShareEntity> findBySessionIdAndRevokedAtIsNull(String sessionId);

    List<ChatShareEntity> findByExpiresAtBeforeAndFilesPurgedAtIsNull(OffsetDateTime now);

    /** 여러 사람이 같이 열어도 한 번씩 세려고 행을 읽어 고치지 않고 한 줄로 올린다. */
    @Modifying
    @Query("UPDATE ChatShareEntity s SET s.viewCount = s.viewCount + 1, s.lastViewedAt = :now WHERE s.id = :id")
    int countView(@Param("id") UUID id, @Param("now") OffsetDateTime now);
}
