package com.platform.repository;

import com.platform.domain.WorkflowEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowRepository extends JpaRepository<WorkflowEntity, String> {
    List<WorkflowEntity> findByIsActiveTrueOrderByName();
    List<WorkflowEntity> findByDomainAndIsActiveTrue(String domain);

    // CR-021: 프로젝트 스코핑
    List<WorkflowEntity> findByProjectIdAndIsActiveTrueOrderByName(String projectId);
    List<WorkflowEntity> findByProjectIdIsNullAndIsActiveTrueOrderByName();
    @org.springframework.data.jpa.repository.Query(
        "SELECT w FROM WorkflowEntity w WHERE w.isActive = true AND (w.projectId = :projectId OR w.projectId IS NULL) ORDER BY w.name")
    List<WorkflowEntity> findByProjectIdOrSharedAndIsActiveTrue(String projectId);

    // CR-022: 사용자별 리소스 소유
    List<WorkflowEntity> findByCreatedByAndIsActiveTrue(String createdBy);
}
