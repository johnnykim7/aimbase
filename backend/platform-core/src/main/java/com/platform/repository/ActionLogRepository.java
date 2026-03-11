package com.platform.repository;

import com.platform.domain.ActionLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ActionLogRepository extends JpaRepository<ActionLogEntity, UUID> {
    Page<ActionLogEntity> findBySessionIdOrderByExecutedAtDesc(String sessionId, Pageable pageable);
    Page<ActionLogEntity> findByIntentOrderByExecutedAtDesc(String intent, Pageable pageable);
    Page<ActionLogEntity> findByStatusOrderByExecutedAtDesc(String status, Pageable pageable);
    Page<ActionLogEntity> findAllByOrderByExecutedAtDesc(Pageable pageable);
}
