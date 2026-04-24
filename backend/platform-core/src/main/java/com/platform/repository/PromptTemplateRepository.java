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

    /* --- CR-049 PRD-305: scope 별 조회 --- */

    /** 특정 scope 의 최신 활성 레코드를 key 로 조회. */
    @Query("SELECT p FROM PromptTemplateEntity p " +
           "WHERE p.pk.key = :key AND p.isActive = true AND p.scope = :scope " +
           "  AND (:projectId IS NULL OR p.projectId = :projectId) " +
           "ORDER BY p.pk.version DESC")
    List<PromptTemplateEntity> findActiveByKeyAndScope(String key, String scope, String projectId);

    /** scope / projectId 필터링으로 목록 조회 (관리 UI 용). */
    @Query("SELECT p FROM PromptTemplateEntity p " +
           "WHERE p.scope = :scope " +
           "  AND (:projectId IS NULL OR p.projectId = :projectId) " +
           "  AND p.isActive = true " +
           "ORDER BY p.pk.key, p.pk.version DESC")
    List<PromptTemplateEntity> findActiveByScope(String scope, String projectId);

    /** 특정 프로젝트의 활성 지침 전부. */
    @Query("SELECT p FROM PromptTemplateEntity p " +
           "WHERE p.scope = 'PROJECT' AND p.projectId = :projectId AND p.isActive = true " +
           "ORDER BY p.pk.key, p.pk.version DESC")
    List<PromptTemplateEntity> findActiveProjectInstructions(String projectId);
}
