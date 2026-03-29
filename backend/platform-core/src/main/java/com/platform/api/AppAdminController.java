package com.platform.api;

import com.platform.domain.master.AppEntity;
import com.platform.domain.master.TenantEntity;
import com.platform.repository.master.AppRepository;
import com.platform.repository.master.TenantRepository;
import com.platform.tenant.TenantDataSourceManager;
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

/**
 * 소비앱 어드민 전용 Tenant 셀프서비스 API.
 * /api/v1/apps/{appId}/** — APP_ADMIN 이상 접근 가능
 */
@RestController
@RequestMapping("/api/v1/apps/{appId}")
@Tag(name = "App Admin", description = "소비앱 어드민 전용 Tenant 셀프서비스 API")
public class AppAdminController {

    private final AppRepository appRepository;
    private final TenantRepository tenantRepository;
    private final TenantOnboardingService tenantOnboardingService;
    private final TenantDataSourceManager tenantDataSourceManager;

    @Value("${platform.default-db-host:localhost}")
    private String defaultDbHost;

    @Value("${platform.default-db-port:5432}")
    private int defaultDbPort;

    @Value("${platform.default-db-username:platform}")
    private String defaultDbUsername;

    @Value("${platform.default-db-password:platform}")
    private String defaultDbPassword;

    public AppAdminController(AppRepository appRepository,
                               TenantRepository tenantRepository,
                               TenantOnboardingService tenantOnboardingService,
                               TenantDataSourceManager tenantDataSourceManager) {
        this.appRepository = appRepository;
        this.tenantRepository = tenantRepository;
        this.tenantOnboardingService = tenantOnboardingService;
        this.tenantDataSourceManager = tenantDataSourceManager;
    }

    // ─── Tenant 셀프서비스 CRUD ──────────────────────────────────────

    @GetMapping("/tenants")
    @Operation(summary = "App 하위 테넌트 목록 조회")
    public ApiResponse<List<TenantEntity>> listTenants(
            @PathVariable String appId,
            @RequestParam(required = false) String status) {
        validateApp(appId);
        if (status != null) {
            return ApiResponse.ok(tenantRepository.findByAppIdAndStatus(appId, status));
        }
        return ApiResponse.ok(tenantRepository.findByAppId(appId));
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "테넌트 셀프서비스 생성")
    public ApiResponse<TenantOnboardingResult> createTenant(
            @PathVariable String appId,
            @Valid @RequestBody TenantCreateRequest request) {
        AppEntity app = validateApp(appId);

        // 테넌트 수 제한 체크
        long currentCount = tenantRepository.countByAppId(appId);
        if (currentCount >= app.getMaxTenants()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Tenant limit reached: " + currentCount + "/" + app.getMaxTenants());
        }

        String tenantId = request.tenantId();
        if (tenantRepository.existsById(tenantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant already exists: " + tenantId);
        }

        TenantOnboardingRequest onboardingRequest = TenantOnboardingRequest.withLocalDb(
            tenantId, appId, request.name(), request.adminEmail(),
            request.initialAdminPassword(), request.plan(),
            defaultDbHost, defaultDbPort, defaultDbUsername, defaultDbPassword
        );

        TenantOnboardingResult result = tenantOnboardingService.provision(onboardingRequest);
        return ApiResponse.ok(result);
    }

    @GetMapping("/tenants/{tenantId}")
    @Operation(summary = "테넌트 상세 조회")
    public ApiResponse<TenantEntity> getTenant(
            @PathVariable String appId,
            @PathVariable String tenantId) {
        validateApp(appId);
        TenantEntity tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId));

        validateTenantBelongsToApp(tenant, appId);
        return ApiResponse.ok(tenant);
    }

    @PutMapping("/tenants/{tenantId}")
    @Operation(summary = "테넌트 정보 수정")
    public ApiResponse<TenantEntity> updateTenant(
            @PathVariable String appId,
            @PathVariable String tenantId,
            @RequestBody TenantUpdateRequest request) {
        validateApp(appId);
        TenantEntity tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId));

        validateTenantBelongsToApp(tenant, appId);

        if (request.name() != null) tenant.setName(request.name());
        if (request.adminEmail() != null) tenant.setAdminEmail(request.adminEmail());
        return ApiResponse.ok(tenantRepository.save(tenant));
    }

    @PostMapping("/tenants/{tenantId}/suspend")
    @Operation(summary = "테넌트 중지")
    public ApiResponse<Map<String, String>> suspendTenant(
            @PathVariable String appId,
            @PathVariable String tenantId) {
        validateApp(appId);
        TenantEntity tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId));

        validateTenantBelongsToApp(tenant, appId);

        tenant.setStatus("suspended");
        tenantRepository.save(tenant);
        tenantDataSourceManager.removeTenantDataSource(tenantId);

        return ApiResponse.ok(Map.of("status", "suspended", "tenantId", tenantId));
    }

    @PostMapping("/tenants/{tenantId}/activate")
    @Operation(summary = "테넌트 활성화")
    public ApiResponse<Map<String, String>> activateTenant(
            @PathVariable String appId,
            @PathVariable String tenantId) {
        validateApp(appId);
        TenantEntity tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId));

        validateTenantBelongsToApp(tenant, appId);

        tenant.setStatus("active");
        tenantRepository.save(tenant);
        tenantDataSourceManager.addTenantDataSource(
            tenantId, tenant.getDbHost(), tenant.getDbPort(),
            tenant.getDbName(), tenant.getDbUsername(), tenant.getDbPasswordEncrypted()
        );

        return ApiResponse.ok(Map.of("status", "active", "tenantId", tenantId));
    }

    @DeleteMapping("/tenants/{tenantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "테넌트 삭제 (DB 포함)")
    public void deleteTenant(
            @PathVariable String appId,
            @PathVariable String tenantId) {
        validateApp(appId);
        TenantEntity tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found: " + tenantId));

        validateTenantBelongsToApp(tenant, appId);
        tenantOnboardingService.deprovision(tenantId);
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private AppEntity validateApp(String appId) {
        AppEntity app = appRepository.findById(appId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "App not found: " + appId));

        if (!"active".equals(app.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "App is not active: " + appId);
        }
        return app;
    }

    private void validateTenantBelongsToApp(TenantEntity tenant, String appId) {
        if (!appId.equals(tenant.getAppId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Tenant " + tenant.getId() + " does not belong to app " + appId);
        }
    }

    // ─── Request Records ─────────────────────────────────────────────

    public record TenantCreateRequest(
        @NotBlank String tenantId,
        @NotBlank String name,
        @NotBlank String adminEmail,
        @NotBlank String initialAdminPassword,
        String plan
    ) {}

    public record TenantUpdateRequest(
        String name,
        String adminEmail
    ) {}
}
