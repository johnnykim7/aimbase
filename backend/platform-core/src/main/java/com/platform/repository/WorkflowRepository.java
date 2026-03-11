package com.platform.repository;

import com.platform.domain.WorkflowEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowRepository extends JpaRepository<WorkflowEntity, String> {
    List<WorkflowEntity> findByIsActiveTrueOrderByName();
    List<WorkflowEntity> findByDomainAndIsActiveTrue(String domain);
}
