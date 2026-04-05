package com.platform.repository;

import com.platform.domain.ContextRecipeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * CR-029: 컨텍스트 레시피 저장소.
 */
@Repository
public interface ContextRecipeRepository extends JpaRepository<ContextRecipeEntity, String> {

    List<ContextRecipeEntity> findByDomainAppAndActiveTrue(String domainApp);

    List<ContextRecipeEntity> findByActiveTrue();

    Optional<ContextRecipeEntity> findByDomainAppAndScopeTypeAndActiveTrue(
            String domainApp, String scopeType);
}
