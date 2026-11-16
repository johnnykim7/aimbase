package com.platform.repository.master;

import com.platform.domain.master.CliAccountTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CliAccountTokenRepository extends JpaRepository<CliAccountTokenEntity, Long> {
    List<CliAccountTokenEntity> findByTenantId(String tenantId);
}
