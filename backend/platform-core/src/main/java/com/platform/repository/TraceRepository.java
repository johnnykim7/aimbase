package com.platform.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import com.platform.domain.TraceEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public interface TraceRepository extends JpaRepository<TraceEntity, UUID> {

    Page<TraceEntity> findBySessionId(String sessionId, Pageable pageable);

    Page<TraceEntity> findByModel(String model, Pageable pageable);

    Page<TraceEntity> findByCreatedAtBetween(OffsetDateTime from, OffsetDateTime to, Pageable pageable);

    Page<TraceEntity> findBySessionIdAndModel(String sessionId, String model, Pageable pageable);

    Page<TraceEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** CR-204 BIZ-292: 대화 완전 삭제. 삭제 표시 여부와 관계없이 그 대화의 행을 모두 지운다. */
    @Modifying
    @Query("DELETE FROM TraceEntity t WHERE t.sessionId = :sessionId")
    int purgeBySessionId(@Param("sessionId") String sessionId);
}
