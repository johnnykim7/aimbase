package com.platform.repository;

import com.platform.domain.PlanEntity;
import com.platform.domain.PlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CR-033 PRD-222~224: Plan Repository.
 */
@Repository
public interface PlanRepository extends JpaRepository<PlanEntity, UUID> {

    Optional<PlanEntity> findBySessionIdAndStatusIn(String sessionId, List<PlanStatus> statuses);

    Optional<PlanEntity> findTopBySessionIdOrderByCreatedAtDesc(String sessionId);

    List<PlanEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    long countBySessionIdAndStatusIn(String sessionId, List<PlanStatus> statuses);
}
