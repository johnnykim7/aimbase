package com.platform.repository;

import com.platform.domain.ToolExecutionLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * CR-029: 도구 실행 이력 저장소.
 */
@Repository
public interface ToolExecutionLogRepository extends JpaRepository<ToolExecutionLogEntity, UUID> {

    List<ToolExecutionLogEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    List<ToolExecutionLogEntity> findByWorkflowRunIdOrderByCreatedAtAsc(String workflowRunId);

    List<ToolExecutionLogEntity> findBySessionIdAndTurnNumberOrderBySequenceInTurnAsc(
            String sessionId, Integer turnNumber);

    Page<ToolExecutionLogEntity> findBySessionId(String sessionId, Pageable pageable);

    Page<ToolExecutionLogEntity> findByToolName(String toolName, Pageable pageable);
}
