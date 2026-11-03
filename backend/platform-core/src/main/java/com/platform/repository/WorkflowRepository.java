package com.platform.repository;

import com.platform.domain.WorkflowEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowRepository extends JpaRepository<WorkflowEntity, String> {
    List<WorkflowEntity> findByIsActiveTrueOrderByName();

    // BIZ-116: 삭제는 비활성화라 정의 조회와 새 실행은 활성만 본다
    Optional<WorkflowEntity> findByIdAndIsActiveTrue(String id);
    boolean existsByIdAndIsActiveTrue(String id);
    Page<WorkflowEntity> findByIsActiveTrue(Pageable pageable);

    List<WorkflowEntity> findByDomainAndIsActiveTrue(String domain);

    // CR-021: 프로젝트 스코핑
    List<WorkflowEntity> findByProjectIdAndIsActiveTrueOrderByName(String projectId);
    List<WorkflowEntity> findByProjectIdIsNullAndIsActiveTrueOrderByName();
    @org.springframework.data.jpa.repository.Query(
        "SELECT w FROM WorkflowEntity w WHERE w.isActive = true AND (w.projectId = :projectId OR w.projectId IS NULL) ORDER BY w.name")
    List<WorkflowEntity> findByProjectIdOrSharedAndIsActiveTrue(String projectId);

    // CR-022: 사용자별 리소스 소유
    List<WorkflowEntity> findByCreatedByAndIsActiveTrue(String createdBy);

    // CR-164 BIZ-131: 이벤트 트리거를 단 활성 워크플로우
    @org.springframework.data.jpa.repository.Query(value =
        "SELECT * FROM workflows WHERE is_active = true AND trigger_config ->> 'type' = :type "
            + "AND trigger_config ->> 'event' = :event ORDER BY id", nativeQuery = true)
    List<WorkflowEntity> findActiveByTrigger(@org.springframework.data.repository.query.Param("type") String type,
                                             @org.springframework.data.repository.query.Param("event") String event);
}
