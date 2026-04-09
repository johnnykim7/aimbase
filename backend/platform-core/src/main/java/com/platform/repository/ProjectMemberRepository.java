package com.platform.repository;

import com.platform.domain.ProjectMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProjectMemberRepository extends JpaRepository<ProjectMemberEntity, UUID> {
    List<ProjectMemberEntity> findByProjectId(String projectId);
    List<ProjectMemberEntity> findByUserId(String userId);
    Optional<ProjectMemberEntity> findByProjectIdAndUserId(String projectId, String userId);
    void deleteByProjectIdAndUserId(String projectId, String userId);
}
