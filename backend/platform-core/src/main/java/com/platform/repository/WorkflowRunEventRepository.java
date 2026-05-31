package com.platform.repository;

import com.platform.domain.WorkflowRunEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowRunEventRepository extends JpaRepository<WorkflowRunEventEntity, Long> {

    /**
     * CR-090: 특정 run 의 시간순 이벤트 (created_at, id ASC tiebreaker).
     */
    List<WorkflowRunEventEntity> findByRunIdOrderByCreatedAtAscIdAsc(UUID runId);
}
