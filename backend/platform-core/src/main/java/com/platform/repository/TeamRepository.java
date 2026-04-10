package com.platform.repository;

import com.platform.domain.TeamEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * CR-039 PRD-265/266: 팀 리포지토리.
 */
public interface TeamRepository extends JpaRepository<TeamEntity, UUID> {

    List<TeamEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    List<TeamEntity> findBySessionIdAndStatus(String sessionId, String status);

    long countBySessionIdAndStatus(String sessionId, String status);
}
