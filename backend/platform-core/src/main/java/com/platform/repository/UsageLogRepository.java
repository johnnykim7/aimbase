package com.platform.repository;

import com.platform.domain.UsageLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public interface UsageLogRepository extends JpaRepository<UsageLogEntity, UUID> {
    Page<UsageLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT SUM(u.costUsd) FROM UsageLogEntity u WHERE u.createdAt >= :from")
    java.math.BigDecimal sumCostUsdSince(OffsetDateTime from);

    @Query("SELECT SUM(u.inputTokens + u.outputTokens) FROM UsageLogEntity u WHERE u.createdAt >= :from")
    Long sumTokensSince(OffsetDateTime from);
}
