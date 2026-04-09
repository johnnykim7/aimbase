package com.platform.repository;

import com.platform.domain.ProjectResourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectResourceRepository extends JpaRepository<ProjectResourceEntity, UUID> {
    List<ProjectResourceEntity> findByProjectId(String projectId);
    List<ProjectResourceEntity> findByProjectIdAndResourceType(String projectId, String resourceType);

    Optional<ProjectResourceEntity> findByProjectIdAndResourceTypeAndResourceId(
            String projectId, String resourceType, String resourceId);

    void deleteByProjectIdAndResourceTypeAndResourceId(
            String projectId, String resourceType, String resourceId);

    @Query("SELECT pr.resourceId FROM ProjectResourceEntity pr WHERE pr.projectId = :projectId AND pr.resourceType = :resourceType")
    List<String> findResourceIdsByProjectIdAndResourceType(String projectId, String resourceType);
}
