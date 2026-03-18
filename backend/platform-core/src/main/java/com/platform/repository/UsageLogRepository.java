package com.platform.repository;

import com.platform.domain.UsageLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface UsageLogRepository extends JpaRepository<UsageLogEntity, UUID> {
    Page<UsageLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT SUM(u.costUsd) FROM UsageLogEntity u WHERE u.createdAt >= :from")
    java.math.BigDecimal sumCostUsdSince(OffsetDateTime from);

    @Query("SELECT SUM(u.inputTokens + u.outputTokens) FROM UsageLogEntity u WHERE u.createdAt >= :from")
    Long sumTokensSince(OffsetDateTime from);

    @Query(value = "SELECT u.model, SUM(u.input_tokens) AS total_input, SUM(u.output_tokens) AS total_output " +
            "FROM usage_logs u WHERE u.created_at > :since GROUP BY u.model ORDER BY SUM(u.input_tokens + u.output_tokens) DESC",
            nativeQuery = true)
    List<Object[]> aggregateByModel(@Param("since") Instant since);

    @Query(value = "SELECT CAST(u.created_at AS DATE) AS log_date, u.model, " +
            "SUM(u.input_tokens) AS total_input, SUM(u.output_tokens) AS total_output " +
            "FROM usage_logs u WHERE u.created_at > :since " +
            "GROUP BY CAST(u.created_at AS DATE), u.model ORDER BY log_date DESC, u.model",
            nativeQuery = true)
    List<Object[]> aggregateByDateAndModel(@Param("since") Instant since);
}
