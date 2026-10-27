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

    /**
     * CR-102: 전체 워크플로우 횡단 run 목록 — workflow_id / status 필터 옵션, 최신순.
     * CR-130: {@code runType} 추가 — "chat"=채팅 run(workflowId IS NULL),
     * "workflow"=워크플로우 run(workflowId IS NOT NULL), null=전체.
     * 서버측 필터라 현재 페이지가 아니라 전체 기준으로 걸러진다.
     */
    @Query("""
            SELECT r FROM WorkflowRunEntity r
            WHERE (:workflowId IS NULL OR r.workflowId = :workflowId)
              AND (:status IS NULL OR r.status = :status)
              AND (:runType IS NULL
                   OR (:runType = 'chat' AND r.workflowId IS NULL)
                   OR (:runType = 'workflow' AND r.workflowId IS NOT NULL))
            ORDER BY r.startedAt DESC
            """)
    Page<WorkflowRunEntity> searchRuns(@Param("workflowId") String workflowId,
                                       @Param("status") String status,
                                       @Param("runType") String runType,
                                       Pageable pageable);
    List<WorkflowRunEntity> findBySessionId(String sessionId);
    Page<WorkflowRunEntity> findByStatusOrderByStartedAtDesc(String status, Pageable pageable);

    /** CR-065: 부모 run 의 자식 서브워크플로우 run 트리 조회. */
    List<WorkflowRunEntity> findByParentRunIdOrderByStartedAtAsc(UUID parentRunId);

    void deleteByWorkflowId(String workflowId);
}
