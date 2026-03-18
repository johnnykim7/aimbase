package com.platform.repository;

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
}
