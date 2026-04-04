package com.platform.repository;

import com.platform.domain.IngestionLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface IngestionLogRepository extends JpaRepository<IngestionLogEntity, UUID> {
    Page<IngestionLogEntity> findBySourceIdOrderByStartedAtDesc(String sourceId, Pageable pageable);
    void deleteBySourceId(String sourceId);
}
