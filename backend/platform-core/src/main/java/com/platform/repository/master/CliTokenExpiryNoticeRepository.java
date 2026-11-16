package com.platform.repository.master;

import com.platform.domain.master.CliTokenExpiryNoticeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public interface CliTokenExpiryNoticeRepository extends JpaRepository<CliTokenExpiryNoticeEntity, Long> {
    boolean existsByTenantIdAndAgentNameAndConfigDirAndExpiresAtAndThresholdDays(
            String tenantId, String agentName, String configDir, OffsetDateTime expiresAt, int thresholdDays);
}
