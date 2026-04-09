package com.platform.repository;

import com.platform.domain.SubagentRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * CR-030 PRD-207: 서브에이전트 실행 기록 Repository.
 */
@Repository
public interface SubagentRunRepository extends JpaRepository<SubagentRunEntity, UUID> {

    List<SubagentRunEntity> findByParentSessionIdOrderByStartedAtDesc(String parentSessionId);

    List<SubagentRunEntity> findByStatus(String status);

    List<SubagentRunEntity> findByParentSessionIdAndStatus(String parentSessionId, String status);
}
