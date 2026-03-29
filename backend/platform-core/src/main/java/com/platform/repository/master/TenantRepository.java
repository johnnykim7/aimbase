package com.platform.repository.master;

import com.platform.domain.master.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<TenantEntity, String> {
    List<TenantEntity> findByStatus(String status);
    List<TenantEntity> findByAppId(String appId);
    List<TenantEntity> findByAppIdAndStatus(String appId, String status);
    long countByAppId(String appId);
    Optional<TenantEntity> findByDbName(String dbName);
    boolean existsByAdminEmail(String adminEmail);
}
