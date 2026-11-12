package com.platform.repository;

import com.platform.domain.ChatProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatProjectRepository extends JpaRepository<ChatProjectEntity, UUID> {

    List<ChatProjectEntity> findByOwnerRef(String ownerRef);

    Optional<ChatProjectEntity> findByIdAndOwnerRef(UUID id, String ownerRef);

    /** CR-178: 프로젝트 목록 행의 자료 수. [project_id, count] */
    @Query("SELECT f.projectId, COUNT(f) FROM ChatProjectFileEntity f WHERE f.projectId IN :projectIds GROUP BY f.projectId")
    List<Object[]> countFilesByProject(@Param("projectIds") List<UUID> projectIds);
}
