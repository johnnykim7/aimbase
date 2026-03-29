package com.platform.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.*;
import java.util.function.Function;

/**
 * Tenant DB → App DB fallback 리소스 조회 서비스.
 *
 * 리소스 해석 우선순위: Tenant 설정 > App 공통 설정
 * - 단건 조회: Tenant DB에 있으면 사용, 없으면 App DB에서 조회
 * - 목록 조회: Tenant DB 결과 + App DB 결과 병합 (Tenant 쪽 우선, ID 중복 제거)
 */
@Service
public class FallbackResourceService {

    private static final Logger log = LoggerFactory.getLogger(FallbackResourceService.class);

    private final AppDataSourceManager appDataSourceManager;

    public FallbackResourceService(AppDataSourceManager appDataSourceManager) {
        this.appDataSourceManager = appDataSourceManager;
    }

    /**
     * 단건 조회 with fallback.
     * tenantJdbc에서 먼저 조회, 없으면 appId의 App DB에서 조회.
     */
    public <T> Optional<T> findWithFallback(
            JdbcTemplate tenantJdbc,
            String appId,
            String sql,
            RowMapper<T> rowMapper,
            Object... params) {

        // 1. Tenant DB에서 조회
        List<T> tenantResult = tenantJdbc.query(sql, rowMapper, params);
        if (!tenantResult.isEmpty()) {
            return Optional.of(tenantResult.get(0));
        }

        // 2. App DB fallback
        if (appId == null) return Optional.empty();
        DataSource appDs = appDataSourceManager.getAppDataSource(appId);
        if (appDs == null) return Optional.empty();

        try {
            JdbcTemplate appJdbc = new JdbcTemplate(appDs);
            List<T> appResult = appJdbc.query(sql, rowMapper, params);
            if (!appResult.isEmpty()) {
                log.debug("Resource found in App DB (fallback): appId={}", appId);
                return Optional.of(appResult.get(0));
            }
        } catch (Exception e) {
            log.warn("App DB fallback query failed for appId={}: {}", appId, e.getMessage());
        }

        return Optional.empty();
    }

    /**
     * 목록 조회 with fallback 병합.
     * Tenant DB 결과를 우선하고, App DB에서 동일 ID가 아닌 리소스만 추가.
     */
    public <T> List<T> listWithFallback(
            JdbcTemplate tenantJdbc,
            String appId,
            String sql,
            RowMapper<T> rowMapper,
            Function<T, String> idExtractor) {

        // 1. Tenant DB 결과
        List<T> tenantResults = tenantJdbc.query(sql, rowMapper);
        Set<String> tenantIds = new HashSet<>();
        for (T item : tenantResults) {
            tenantIds.add(idExtractor.apply(item));
        }

        // 2. App DB fallback — Tenant에 없는 것만 추가
        if (appId == null) return tenantResults;
        DataSource appDs = appDataSourceManager.getAppDataSource(appId);
        if (appDs == null) return tenantResults;

        try {
            JdbcTemplate appJdbc = new JdbcTemplate(appDs);
            List<T> appResults = appJdbc.query(sql, rowMapper);

            List<T> merged = new ArrayList<>(tenantResults);
            for (T item : appResults) {
                if (!tenantIds.contains(idExtractor.apply(item))) {
                    merged.add(item);
                }
            }
            return merged;
        } catch (Exception e) {
            log.warn("App DB fallback list query failed for appId={}: {}", appId, e.getMessage());
            return tenantResults;
        }
    }

    /**
     * App DB 전용 JdbcTemplate 획득 (App 리소스 직접 관리용).
     */
    public Optional<JdbcTemplate> getAppJdbcTemplate(String appId) {
        if (appId == null) return Optional.empty();
        DataSource appDs = appDataSourceManager.getAppDataSource(appId);
        if (appDs == null) return Optional.empty();
        return Optional.of(new JdbcTemplate(appDs));
    }
}
