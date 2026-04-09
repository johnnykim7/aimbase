package com.platform.api;

import com.platform.auth.UserPrincipal;
import com.platform.domain.master.ApiKeyEntity;
import com.platform.repository.master.ApiKeyRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/platform/api-keys")
@Tag(name = "Platform API Keys", description = "시스템 API Key 관리 — Master DB (CR-025)")
public class ApiKeyController {

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyController(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "API Key 생성 (테넌트 바인딩)")
    public ApiResponse<Map<String, Object>> create(
            @Valid @RequestBody CreateApiKeyRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        String rawKey = "plat-" + UUID.randomUUID().toString().replace("-", "");
        String hash = sha256(rawKey);
        String prefix = rawKey.substring(0, 8);

        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setName(request.name());
        entity.setKeyHash(hash);
        entity.setKeyPrefix(prefix);
        entity.setTenantId(request.tenantId());
        entity.setDomainApp(request.domainApp());
        entity.setScope(request.scope());
        entity.setExpiresAt(request.expiresAt());
        entity.setActive(true);
        if (principal != null) {
            entity.setCreatedBy(principal.getEmail());
        }
        apiKeyRepository.save(entity);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entity.getId());
        result.put("name", entity.getName());
        result.put("apiKey", rawKey);
        result.put("keyPrefix", prefix);
        result.put("tenantId", entity.getTenantId());
        result.put("domainApp", entity.getDomainApp());
        result.put("scope", entity.getScope());
        result.put("expiresAt", entity.getExpiresAt());
        result.put("createdAt", entity.getCreatedAt());
        result.put("note", "이 키는 다시 조회할 수 없습니다. 안전한 곳에 저장하세요.");

        return ApiResponse.ok(result);
    }

    @GetMapping
    @Operation(summary = "API Key 목록 조회")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String tenantId) {
        List<ApiKeyEntity> keys = tenantId != null
                ? apiKeyRepository.findAllByTenantIdAndIsActiveTrueOrderByCreatedAtDesc(tenantId)
                : apiKeyRepository.findAllByIsActiveTrueOrderByCreatedAtDesc();
        List<Map<String, Object>> result = keys.stream().map(this::toListItem).toList();
        return ApiResponse.ok(result);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "API Key 폐기")
    public void revoke(@PathVariable String id) {
        ApiKeyEntity entity = apiKeyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "API Key not found: " + id));
        entity.setActive(false);
        apiKeyRepository.save(entity);
    }

    @PostMapping("/{id}/regenerate")
    @Operation(summary = "API Key 재발급")
    public ApiResponse<Map<String, Object>> regenerate(
            @PathVariable String id,
            @AuthenticationPrincipal UserPrincipal principal) {

        ApiKeyEntity entity = apiKeyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "API Key not found: " + id));

        // 기존 키 폐기
        entity.setActive(false);
        apiKeyRepository.save(entity);

        // 동일 설정으로 새 키 발급
        String rawKey = "plat-" + UUID.randomUUID().toString().replace("-", "");
        String hash = sha256(rawKey);
        String prefix = rawKey.substring(0, 8);

        ApiKeyEntity newEntity = new ApiKeyEntity();
        newEntity.setId(UUID.randomUUID().toString());
        newEntity.setName(entity.getName());
        newEntity.setKeyHash(hash);
        newEntity.setKeyPrefix(prefix);
        newEntity.setTenantId(entity.getTenantId());
        newEntity.setDomainApp(entity.getDomainApp());
        newEntity.setScope(entity.getScope());
        newEntity.setExpiresAt(entity.getExpiresAt());
        newEntity.setActive(true);
        if (principal != null) {
            newEntity.setCreatedBy(principal.getEmail());
        }
        apiKeyRepository.save(newEntity);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", newEntity.getId());
        result.put("name", newEntity.getName());
        result.put("apiKey", rawKey);
        result.put("keyPrefix", prefix);
        result.put("tenantId", newEntity.getTenantId());
        result.put("domainApp", newEntity.getDomainApp());
        result.put("scope", newEntity.getScope());
        result.put("expiresAt", newEntity.getExpiresAt());
        result.put("createdAt", newEntity.getCreatedAt());
        result.put("note", "이 키는 다시 조회할 수 없습니다. 안전한 곳에 저장하세요.");

        return ApiResponse.ok(result);
    }

    private Map<String, Object> toListItem(ApiKeyEntity k) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", k.getId());
        map.put("name", k.getName());
        map.put("keyPrefix", k.getKeyPrefix());
        map.put("tenantId", k.getTenantId());
        map.put("domainApp", k.getDomainApp());
        map.put("scope", k.getScope());
        map.put("lastUsedAt", k.getLastUsedAt());
        map.put("expiresAt", k.getExpiresAt());
        map.put("isActive", k.isActive());
        map.put("createdBy", k.getCreatedBy());
        map.put("createdAt", k.getCreatedAt());
        return map;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record CreateApiKeyRequest(
            @NotBlank String name,
            @NotBlank String domainApp,
            String tenantId,
            Map<String, Object> scope,
            OffsetDateTime expiresAt
    ) {}
}
