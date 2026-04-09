package com.platform.repository;

import com.platform.domain.PromptTemplateEntity;
import com.platform.domain.PromptTemplateEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * CR-036 PRD-249: 프롬프트 템플릿 Repository.
 */
@Repository
public interface PromptTemplateRepository extends JpaRepository<PromptTemplateEntity, PromptTemplateEntityId> {

    @Query("SELECT p FROM PromptTemplateEntity p WHERE p.pk.key = :key AND p.isActive = true ORDER BY p.pk.version DESC")
    Optional<PromptTemplateEntity> findActiveByKey(String key);

    @Query("SELECT p FROM PromptTemplateEntity p WHERE p.pk.key = :key ORDER BY p.pk.version DESC")
    List<PromptTemplateEntity> findAllVersionsByKey(String key);

    List<PromptTemplateEntity> findByCategoryAndIsActiveTrue(String category);

    List<PromptTemplateEntity> findByIsActiveTrue();

    @Query("SELECT p FROM PromptTemplateEntity p WHERE p.isActive = true AND p.pk.version = " +
           "(SELECT MAX(p2.pk.version) FROM PromptTemplateEntity p2 WHERE p2.pk.key = p.pk.key AND p2.isActive = true)")
    List<PromptTemplateEntity> findLatestActiveAll();
}
