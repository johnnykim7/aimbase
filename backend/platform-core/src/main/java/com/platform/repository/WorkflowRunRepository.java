package com.platform.repository;

import com.platform.domain.WorkflowRunEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowRunRepository extends JpaRepository<WorkflowRunEntity, UUID> {
    Page<WorkflowRunEntity> findByWorkflowIdOrderByStartedAtDesc(String workflowId, Pageable pageable);
    List<WorkflowRunEntity> findBySessionId(String sessionId);
    Page<WorkflowRunEntity> findByStatusOrderByStartedAtDesc(String status, Pageable pageable);
}
