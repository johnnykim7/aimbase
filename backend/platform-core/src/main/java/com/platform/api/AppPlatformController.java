package com.platform.api;

import com.platform.app.AppDataSourceManager;
import com.platform.app.onboarding.AppOnboardingRequest;
import com.platform.app.onboarding.AppOnboardingResult;
import com.platform.app.onboarding.AppOnboardingService;
import com.platform.domain.master.AppEntity;
import com.platform.domain.master.TenantEntity;
import com.platform.repository.master.AppRepository;
import com.platform.repository.master.TenantRepository;
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
 * 슈퍼어드민 전용 App(소비앱) 관리 API.
 * /api/v1/platform/apps/** — ROLE_SUPER_ADMIN 전용
 */
@RestController
@RequestMapping("/api/v1/platform/apps")
@Tag(name = "Platform App Admin", description = "슈퍼어드민 전용 App(소비앱) 관리 API")
public class AppPlatformController {

    private final AppRepository appRepository;
    private final TenantRepository tenantRepository;
    private final AppOnboardingService onboardingService;
    private final AppDataSourceManager appDataSourceManager;

    @Value("${platform.default-db-host:localhost}")
    private String defaultDbHost;

    @Value("${platform.default-db-port:5432}")
    private int defaultDbPort;

    @Value("${platform.default-db-username:platform}")
    private String defaultDbUsername;

    @Value("${platform.default-db-password:platform}")
    private String defaultDbPassword;

    public AppPlatformController(AppRepository appRepository,
                                  TenantRepository tenantRepository,
                                  AppOnboardingService onboardingService,
                                  AppDataSourceManager appDataSourceManager) {
        this.appRepository = appRepository;
        this.tenantRepository = tenantRepository;
        this.onboardingService = onboardingService;
        this.appDataSourceManager = appDataSourceManager;
    }

    // ─── App CRUD ─────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "App 목록 조회")
    public ApiResponse<List<AppEntity>> listApps(
            @RequestParam(required = false) String status) {
        if (status != null) {
            return ApiResponse.ok(appRepository.findByStatus(status));
        }
        return ApiResponse.ok(appRepository.findAll());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "App 생성 (자동 프로비저닝)")
    public ApiResponse<AppOnboardingResult> createApp(@Valid @RequestBody AppCreateRequest request) {
        if (appRepository.existsById(request.appId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "App already exists: " + request.appId());
        }

        AppOnboardingRequest onboardingRequest = AppOnboardingRequest.withLocalDb(
            request.appId(),
            request.name(),
            request.description(),
            request.ownerEmail(),
            request.ownerPassword(),
            request.maxTenants(),
            defaultDbHost, defaultDbPort, defaultDbUsername, defaultDbPassword
        );

        AppOnboardingResult result = onboardingService.provision(onboardingRequest);
        return ApiResponse.ok(result);
    }

    @GetMapping("/{appId}")
    @Operation(summary = "App 상세 조회")
    public ApiResponse<Map<String, Object>> getApp(@PathVariable String appId) {
        AppEntity app = appRepository.findById(appId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "App not found: " + appId));

        long tenantCount = tenantRepository.countByAppId(appId);
        List<TenantEntity> tenants = tenantRepository.findByAppId(appId);

        return ApiResponse.ok(Map.of(
            "app", app,
            "tenantCount", tenantCount,
            "tenants", tenants
        ));
    }

    @PutMapping("/{appId}")
    @Operation(summary = "App 정보 수정")
    public ApiResponse<AppEntity> updateApp(@PathVariable String appId,
                                             @RequestBody AppUpdateRequest request) {
        AppEntity app = appRepository.findById(appId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "App not found: " + appId));

        if (request.name() != null) app.setName(request.name());
        if (request.description() != null) app.setDescription(request.description());
        if (request.ownerEmail() != null) app.setOwnerEmail(request.ownerEmail());
        if (request.maxTenants() != null) app.setMaxTenants(request.maxTenants());
        return ApiResponse.ok(appRepository.save(app));
    }

    @PostMapping("/{appId}/suspend")
    @Operation(summary = "App 중지 (하위 테넌트 전체 중지)")
    public ApiResponse<Map<String, String>> suspendApp(@PathVariable String appId) {
        AppEntity app = appRepository.findById(appId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "App not found: " + appId));

        app.setStatus("suspended");
        appRepository.save(app);
        appDataSourceManager.removeAppDataSource(appId);

        return ApiResponse.ok(Map.of("status", "suspended", "appId", appId));
    }

    @PostMapping("/{appId}/activate")
    @Operation(summary = "App 활성화")
    public ApiResponse<Map<String, String>> activateApp(@PathVariable String appId) {
        AppEntity app = appRepository.findById(appId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "App not found: " + appId));

        app.setStatus("active");
        appRepository.save(app);

        appDataSourceManager.addAppDataSource(
            appId, app.getDbHost(), app.getDbPort(), app.getDbName(),
            app.getDbUsername(), app.getDbPasswordEncrypted()
        );

        return ApiResponse.ok(Map.of("status", "active", "appId", appId));
    }

    @DeleteMapping("/{appId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "App 삭제 (DB 포함 완전 삭제)")
    public void deleteApp(@PathVariable String appId) {
        // 하위 테넌트가 있으면 삭제 불가
        long tenantCount = tenantRepository.countByAppId(appId);
        if (tenantCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot delete app with " + tenantCount + " active tenants. Remove tenants first.");
        }
        onboardingService.deprovision(appId);
    }

    // ─── App 하위 테넌트 목록 ─────────────────────────────────────────

    @GetMapping("/{appId}/tenants")
    @Operation(summary = "App 하위 테넌트 목록 조회")
    public ApiResponse<List<TenantEntity>> listAppTenants(
            @PathVariable String appId,
            @RequestParam(required = false) String status) {
        if (!appRepository.existsById(appId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "App not found: " + appId);
        }
        if (status != null) {
            return ApiResponse.ok(tenantRepository.findByAppIdAndStatus(appId, status));
        }
        return ApiResponse.ok(tenantRepository.findByAppId(appId));
    }

    // ─── Request Records ─────────────────────────────────────────────

    public record AppCreateRequest(
        @NotBlank String appId,
        @NotBlank String name,
        String description,
        @NotBlank String ownerEmail,
        @NotBlank String ownerPassword,
        Integer maxTenants
    ) {}

    public record AppUpdateRequest(
        String name,
        String description,
        String ownerEmail,
        Integer maxTenants
    ) {}
}
