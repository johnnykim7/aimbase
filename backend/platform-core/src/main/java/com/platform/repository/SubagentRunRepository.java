package com.platform.repository;

import com.platform.domain.SubagentRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CR-030 PRD-207: 서브에이전트 실행 기록 Repository.
 */
@Repository
public interface SubagentRunRepository extends JpaRepository<SubagentRunEntity, UUID> {

    List<SubagentRunEntity> findByParentSessionIdOrderByStartedAtDesc(String parentSessionId);

    List<SubagentRunEntity> findByStatus(String status);

    List<SubagentRunEntity> findByParentSessionIdAndStatus(String parentSessionId, String status);

    /** CR-093 BIZ-109: 자식 세션 ID 로 부모 run 조회 (depth 카운팅용) */
    Optional<SubagentRunEntity> findFirstByChildSessionId(String childSessionId);
}
