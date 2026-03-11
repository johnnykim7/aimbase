package com.platform.repository.master;

import com.platform.domain.master.TenantAdminEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantAdminRepository extends JpaRepository<TenantAdminEntity, UUID> {
    Optional<TenantAdminEntity> findByEmail(String email);
    boolean existsByEmail(String email);
}
