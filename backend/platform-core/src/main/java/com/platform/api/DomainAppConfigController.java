package com.platform.api;

import com.platform.domain.DomainAppConfigEntity;
import com.platform.repository.DomainAppConfigRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CR-029: Domain App Config CRUD API.
 */
@RestController
@RequestMapping("/api/v1/domain-configs")
@Tag(name = "Domain Config", description = "도메인 앱 기본 설정 (CR-029)")
public class DomainAppConfigController {

    private final DomainAppConfigRepository repository;

    public DomainAppConfigController(DomainAppConfigRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "Domain Config 목록 조회")
    public ApiResponse<List<DomainAppConfigEntity>> list() {
        return ApiResponse.ok(repository.findAll());
    }

    @GetMapping("/{domainApp}")
    @Operation(summary = "Domain Config 상세 조회")
    public ApiResponse<DomainAppConfigEntity> get(@PathVariable String domainApp) {
        return repository.findByDomainApp(domainApp)
                .map(ApiResponse::ok)
                .orElse(ApiResponse.error("도메인 설정을 찾을 수 없습니다: " + domainApp));
    }

    @PostMapping
    @Operation(summary = "Domain Config 생성")
    public ApiResponse<DomainAppConfigEntity> create(@RequestBody DomainAppConfigEntity entity) {
        if (entity.getId() == null || entity.getId().isBlank()) {
            entity.setId("domain-" + entity.getDomainApp());
        }
        return ApiResponse.ok(repository.save(entity));
    }

    @PutMapping("/{domainApp}")
    @Operation(summary = "Domain Config 수정")
    public ApiResponse<DomainAppConfigEntity> update(@PathVariable String domainApp,
                                                      @RequestBody DomainAppConfigEntity entity) {
        return repository.findByDomainApp(domainApp)
                .map(existing -> {
                    existing.setDefaultContextRecipeId(entity.getDefaultContextRecipeId());
                    existing.setDefaultToolAllowlist(entity.getDefaultToolAllowlist());
                    existing.setDefaultToolDenylist(entity.getDefaultToolDenylist());
                    existing.setDefaultPolicyPreset(entity.getDefaultPolicyPreset());
                    existing.setDefaultSessionScope(entity.getDefaultSessionScope());
                    existing.setDefaultRuntime(entity.getDefaultRuntime());
                    existing.setMcpServerIds(entity.getMcpServerIds());
                    existing.setConfig(entity.getConfig());
                    return ApiResponse.ok(repository.save(existing));
                })
                .orElse(ApiResponse.error("도메인 설정을 찾을 수 없습니다: " + domainApp));
    }

    @DeleteMapping("/{domainApp}")
    @Operation(summary = "Domain Config 삭제")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String domainApp) {
        return repository.findByDomainApp(domainApp)
                .map(existing -> {
                    repository.delete(existing);
                    return ApiResponse.<Map<String, Object>>ok(Map.of("deleted", domainApp));
                })
                .orElse(ApiResponse.error("도메인 설정을 찾을 수 없습니다: " + domainApp));
    }
}
