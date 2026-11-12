package com.platform.repository;

import com.platform.domain.ChatProjectFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatProjectFileRepository extends JpaRepository<ChatProjectFileEntity, UUID> {

    List<ChatProjectFileEntity> findByProjectIdOrderByCreatedAtAsc(UUID projectId);

    long countByProjectId(UUID projectId);
}
