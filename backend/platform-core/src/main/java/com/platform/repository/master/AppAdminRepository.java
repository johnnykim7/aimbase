package com.platform.repository.master;

import com.platform.domain.master.AppAdminEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppAdminRepository extends JpaRepository<AppAdminEntity, UUID> {
    List<AppAdminEntity> findByAppId(String appId);
    Optional<AppAdminEntity> findByAppIdAndEmail(String appId, String email);
    boolean existsByAppIdAndEmail(String appId, String email);
}
