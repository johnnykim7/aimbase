package com.platform.api;

import com.platform.domain.master.SubscriptionEntity;
import com.platform.domain.master.TenantEntity;
import com.platform.repository.master.SubscriptionRepository;
import com.platform.repository.master.TenantRepository;
import com.platform.repository.master.TenantUsageSummaryRepository;
import com.platform.tenant.TenantDataSourceManager;
import com.platform.tenant.migration.TenantMigrationInfo;
import com.platform.tenant.migration.TenantMigrationResult;
import com.platform.tenant.migration.TenantMigrationRunner;
import com.platform.tenant.onboarding.TenantOnboardingRequest;
import com.platform.tenant.onboarding.TenantOnboardingResult;
import com.platform.tenant.onboarding.TenantOnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 슈퍼어드민 전용 플랫폼 관리 API.
 * 테넌트 생성/수정/삭제, 구독 쿼터 관리, 전체 사용량 대시보드.
 *
 * /api/v1/platform/** — SecurityConfig에서 ROLE_SUPER_ADMIN 전용으로 설정 예정 (Phase 5)
 * TenantResolver가 이 경로는 TenantContext 설정을 건너뜀 → Master DB만 사용.
 */
@RestController
@RequestMapping("/api/v1/platform")
@Tag(name = "Platform Admin", description = "슈퍼어드민 전용 플랫폼 관리 API")
public class PlatformController {

    private final TenantRepository tenantRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final TenantUsageSummaryRepository usageSummaryRepository;
    private final TenantOnboardingService onboardingService;
    private final TenantDataSourceManager dataSourceManager;
    private final TenantMigrationRunner migrationRunner;

    @Value("${platform.default-db-host:localhost}")
    private String defaultDbHost;

    @Value("${platform.default-db-port:5432}")
    private int defaultDbPort;

    @Value("${platform.default-db-username:platform}")
    private String defaultDbUsername;

    @Value("${platform.default-db-password:platform}")
    private String defaultDbPassword;

    public PlatformController(TenantRepository tenantRepository,
                               SubscriptionRepository subscriptionRepository,
                               TenantUsageSummaryRepository usageSummaryRepository,
                               TenantOnboardingService onboardingService,
                               TenantDataSourceManager dataSourceManager,
                               TenantMigrationRunner migrationRunner) {
        this.tenantRepository = tenantRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.usageSummaryRepository = usageSummaryRepository;
        this.onboardingService = onboardingService;
        this.dataSourceManager = dataSourceManager;
        this.migrationRunner = migrationRunner;
    }

    // ─── Tenant CRUD ─────────────────────────────────────────────────

    @GetMapping("/tenants")
    @Operation(summary = "테넌트 목록 조회")
    public ApiResponse<List<TenantEntity>> listTenants(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "domain_app") String domainApp) {
        // CR-023: 소비앱별 필터링
        if (domainApp != null) {
            return ApiResponse.ok(tenantRepository.findByDomainApp(domainApp));
        }
        if (status != null) {
            return ApiResponse.ok(tenantRepository.findByStatus(status));
        }
        return ApiResponse.ok(tenantRepository.findAll());
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "테넌트 생성 (자동 프로비저닝)")
    public ApiResponse<TenantOnboardingResult> createTenant(@Valid @RequestBody TenantCreateRequest request) {
        if (tenantRepository.existsById(request.tenantId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant already exists: " + request.tenantId());
        }

        TenantOnboardingRequest onboardingRequest = TenantOnboardingRequest.withLocalDb(
            request.tenantId(),
            request.appId(),
            request.name(),
            request.adminEmail(),
            request.initialAdminPassword(),
            request.plan(),
            defaultDbHost, defaultDbPort, defaultDbUsername, defaultDbPassword
        );

        TenantOnboardingResult result = onboardingService.provision(onboardingRequest);

        // CR-023: 소비앱 메타데이터 설정
        if (request.domainApp() != null && !request.domainApp().isBlank()) {
            tenantRepository.findById(request.tenantId()).ifPresent(t -> {
                t.setDomainApp(request.domainApp());
                tenantRepository.save(t);
            });
        }

        return ApiResponse.ok(result);
    }

    @GetMapping("/tenants/{id}")
    @Operation(summary = "테넌트 상세 조회")
    public ApiResponse<Map<String, Object>> getTenant(@PathVariable String id) {
        TenantEntity tenant = tenantRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + id));

        SubscriptionEntity subscription = subscriptionRepository.findByTenantId(id).orElse(null);
        List<?> usageHistory = usageSummaryRepository.findByPkTenantId(id);

        return ApiResponse.ok(Map.of(
            "tenant", tenant,
            "subscription", subscription != null ? subscription : Map.of(),
            "usageHistory", usageHistory
        ));
    }

    @PutMapping("/tenants/{id}")
    @Operation(summary = "테넌트 정보 수정")
    public ApiResponse<TenantEntity> updateTenant(@PathVariable String id,
                                                    @RequestBody TenantUpdateRequest request) {
        TenantEntity tenant = tenantRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + id));

        if (request.name() != null) tenant.setName(request.name());
        if (request.adminEmail() != null) tenant.setAdminEmail(request.adminEmail());
        if (request.domainApp() != null) tenant.setDomainApp(request.domainApp()); // CR-023
        return ApiResponse.ok(tenantRepository.save(tenant));
    }

    @PostMapping("/tenants/{id}/suspend")
    @Operation(summary = "테넌트 중지")
    public ApiResponse<Map<String, String>> suspendTenant(@PathVariable String id) {
        TenantEntity tenant = tenantRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + id));

        tenant.setStatus("suspended");
        tenantRepository.save(tenant);

        // DataSource 캐시에서 제거 — 이후 요청 자동 거부
        dataSourceManager.removeTenantDataSource(id);

        return ApiResponse.ok(Map.of("status", "suspended", "tenantId", id));
    }

    @PostMapping("/tenants/{id}/activate")
    @Operation(summary = "테넌트 활성화")
    public ApiResponse<Map<String, String>> activateTenant(@PathVariable String id) {
        TenantEntity tenant = tenantRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + id));

        tenant.setStatus("active");
        tenantRepository.save(tenant);

        // DataSource 다시 등록
        dataSourceManager.addTenantDataSource(
            id,
            tenant.getDbHost(), tenant.getDbPort(), tenant.getDbName(),
            tenant.getDbUsername(), tenant.getDbPasswordEncrypted()
        );

        return ApiResponse.ok(Map.of("status", "active", "tenantId", id));
    }

    @DeleteMapping("/tenants/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "테넌트 삭제 (DB 포함 완전 삭제)")
    public void deleteTenant(@PathVariable String id) {
        onboardingService.deprovision(id);
    }

    // ─── Subscription / Quota ────────────────────────────────────────

    @GetMapping("/subscriptions")
    @Operation(summary = "구독 목록 조회")
    public ApiResponse<List<SubscriptionEntity>> listSubscriptions() {
        return ApiResponse.ok(subscriptionRepository.findAll());
    }

    @PutMapping("/subscriptions/{tenantId}")
    @Operation(summary = "테넌트 쿼터/플랜 변경")
    public ApiResponse<SubscriptionEntity> updateSubscription(
            @PathVariable String tenantId,
            @RequestBody SubscriptionUpdateRequest request) {
        SubscriptionEntity sub = subscriptionRepository.findByTenantId(tenantId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Subscription not found for tenant: " + tenantId));

        if (request.plan() != null) sub.setPlan(request.plan());
        if (request.llmMonthlyTokenQuota() != null) sub.setLlmMonthlyTokenQuota(request.llmMonthlyTokenQuota());
        if (request.maxConnections() != null) sub.setMaxConnections(request.maxConnections());
        if (request.maxKnowledgeSources() != null) sub.setMaxKnowledgeSources(request.maxKnowledgeSources());
        if (request.maxWorkflows() != null) sub.setMaxWorkflows(request.maxWorkflows());
        if (request.storageGb() != null) sub.setStorageGb(request.storageGb());

        return ApiResponse.ok(subscriptionRepository.save(sub));
    }

    // ─── Usage Dashboard ─────────────────────────────────────────────

    @GetMapping("/usage")
    @Operation(summary = "전체 플랫폼 사용량 대시보드")
    public ApiResponse<Map<String, Object>> getUsageDashboard() {
        long totalTenants = tenantRepository.count();
        long activeTenants = tenantRepository.findByStatus("active").size();
        long totalSubscriptions = subscriptionRepository.count();

        return ApiResponse.ok(Map.of(
            "totalTenants", totalTenants,
            "activeTenants", activeTenants,
            "suspendedTenants", totalTenants - activeTenants,
            "totalSubscriptions", totalSubscriptions
        ));
    }

    // ─── CR-066: Tenant Flyway 자동 재실행 ────────────────────────────

    @PostMapping("/tenants/migrate")
    @Operation(summary = "테넌트 DB Flyway 일괄 마이그레이션 (CR-066)",
            description = "활성 테넌트(기본) 또는 지정 테넌트 목록에 대해 Flyway.migrate() 를 일괄 실행한다. "
                    + "1개 테넌트 실패는 격리되어 나머지 진행. dryRun=true 면 pending 카운트만 반환.")
    public ApiResponse<TenantMigrationResult> migrateTenants(@RequestBody(required = false) TenantMigrateRequest request) {
        boolean dryRun = request != null && Boolean.TRUE.equals(request.dryRun());
        List<String> tenantIds = (request != null) ? request.tenantIds() : null;

        TenantMigrationResult result = (tenantIds == null || tenantIds.isEmpty())
                ? migrationRunner.migrateAll(dryRun)
                : migrationRunner.migrate(tenantIds, dryRun);
        return ApiResponse.ok(result);
    }

    @GetMapping("/tenants/{id}/migrations")
    @Operation(summary = "테넌트 DB Flyway 마이그레이션 현황 (CR-066)",
            description = "flyway_schema_history 기반 적용 이력 + 대기 중(pending) 버전 목록 조회.")
    public ApiResponse<TenantMigrationInfo> getTenantMigrations(@PathVariable String id) {
        try {
            return ApiResponse.ok(migrationRunner.describe(id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // ─── Request Records ─────────────────────────────────────────────

    public record TenantCreateRequest(
        @NotBlank String tenantId,
        String appId,
        @NotBlank String name,
        @NotBlank String adminEmail,
        @NotBlank String initialAdminPassword,
        String plan,
        String domainApp // CR-023
    ) {}

    public record TenantUpdateRequest(
        String name,
        String adminEmail,
        String domainApp // CR-023
    ) {}

    public record SubscriptionUpdateRequest(
        String plan,
        Long llmMonthlyTokenQuota,
        Integer maxConnections,
        Integer maxKnowledgeSources,
        Integer maxWorkflows,
        Integer storageGb
    ) {}

    /** CR-066: 테넌트 일괄 마이그레이션 요청. body 생략 시 활성 테넌트 전체, dryRun=false 적용. */
    public record TenantMigrateRequest(
        List<String> tenantIds,
        Boolean dryRun
    ) {}
}
