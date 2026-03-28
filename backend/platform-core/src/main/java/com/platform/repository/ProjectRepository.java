package com.platform.repository;

import com.platform.domain.ProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectRepository extends JpaRepository<ProjectEntity, String> {
    List<ProjectEntity> findByIsActiveTrueOrderByName();

    // CR-022: 사용자별 리소스 소유
    List<ProjectEntity> findByCreatedByAndIsActiveTrueOrderByName(String createdBy);
}
