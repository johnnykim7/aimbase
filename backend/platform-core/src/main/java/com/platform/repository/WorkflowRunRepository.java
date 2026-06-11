package com.platform.repository;

import com.platform.domain.WorkflowRunEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity, UUID> {
    Page<WorkflowRunEntity> findByWorkflowIdOrderByStartedAtDesc(String workflowId, Pageable pageable);

    /** CR-102: 전체 워크플로우 횡단 run 목록 — workflow_id / status 필터 옵션, 최신순. */
    @Query("""
            SELECT r FROM WorkflowRunEntity r
            WHERE (:workflowId IS NULL OR r.workflowId = :workflowId)
              AND (:status IS NULL OR r.status = :status)
            ORDER BY r.startedAt DESC
            """)
    Page<WorkflowRunEntity> searchRuns(@Param("workflowId") String workflowId,
                                       @Param("status") String status,
                                       Pageable pageable);
    List<WorkflowRunEntity> findBySessionId(String sessionId);
    Page<WorkflowRunEntity> findByStatusOrderByStartedAtDesc(String status, Pageable pageable);

    /** CR-065: 부모 run 의 자식 서브워크플로우 run 트리 조회. */
    List<WorkflowRunEntity> findByParentRunIdOrderByStartedAtAsc(UUID parentRunId);

    void deleteByWorkflowId(String workflowId);
}
