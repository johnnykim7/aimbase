package com.platform.repository;

import com.platform.domain.PromptEntity;
import com.platform.domain.PromptEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PromptRepository extends JpaRepository<PromptEntity, PromptEntityId> {
    @Query("SELECT p FROM PromptEntity p WHERE p.pk.id = :id ORDER BY p.pk.version DESC")
    List<PromptEntity> findAllVersionsById(String id);

    @Query("SELECT p FROM PromptEntity p WHERE p.pk.id = :id AND p.isActive = true")
    Optional<PromptEntity> findActiveById(String id);

    List<PromptEntity> findByDomainAndIsActiveTrue(String domain);

    // CR-021: 프로젝트 스코핑 — resource_id는 prompt id (pk.id)
    @Query("SELECT p FROM PromptEntity p WHERE p.pk.id IN :ids")
    List<PromptEntity> findByIdIn(List<String> ids);

    // CR-022: 사용자별 리소스 소유
    List<PromptEntity> findByCreatedBy(String createdBy);
}
