package com.platform.repository.master;

import com.platform.domain.master.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, String> {
    Optional<ApiKeyEntity> findByKeyHashAndIsActiveTrue(String keyHash);
    List<ApiKeyEntity> findAllByIsActiveTrueOrderByCreatedAtDesc();
    List<ApiKeyEntity> findAllByTenantIdAndIsActiveTrueOrderByCreatedAtDesc(String tenantId);
}
