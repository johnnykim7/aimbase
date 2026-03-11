package com.platform.repository.master;

import com.platform.domain.master.TenantUsageSummaryEntity;
import com.platform.domain.master.TenantUsageSummaryEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenantUsageSummaryRepository extends JpaRepository<TenantUsageSummaryEntity, TenantUsageSummaryEntityId> {

    List<TenantUsageSummaryEntity> findByPkTenantId(String tenantId);

    Optional<TenantUsageSummaryEntity> findByPkTenantIdAndPkYearMonth(String tenantId, String yearMonth);

    @Query("SELECT SUM(u.totalInputTokens + u.totalOutputTokens) FROM TenantUsageSummaryEntity u WHERE u.pk.tenantId = :tenantId AND u.pk.yearMonth = :yearMonth")
    Optional<Long> sumTokensByTenantIdAndYearMonth(String tenantId, String yearMonth);
}
