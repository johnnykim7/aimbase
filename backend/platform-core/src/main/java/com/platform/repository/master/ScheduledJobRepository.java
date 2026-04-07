package com.platform.repository.master;

import com.platform.domain.master.ScheduledJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * CR-035 PRD-228: Cron 스케줄 작업 레포지토리 (Master DB).
 */
@Repository
public interface ScheduledJobRepository extends JpaRepository<ScheduledJobEntity, String> {

    List<ScheduledJobEntity> findByIsActiveTrueOrderByCreatedAtDesc();

    List<ScheduledJobEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    long countByTenantId(String tenantId);
}
