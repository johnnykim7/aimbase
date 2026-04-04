package com.platform.api;

import com.platform.domain.UserEntity;
import com.platform.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import com.platform.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "사용자 관리")
public class UserController {

    private final UserRepository userRepository;
    private final JdbcTemplate masterJdbcTemplate;

    public UserController(UserRepository userRepository,
                          @Qualifier("masterJdbcTemplate") JdbcTemplate masterJdbcTemplate) {
        this.userRepository = userRepository;
        this.masterJdbcTemplate = masterJdbcTemplate;
    }

    @GetMapping
    @Operation(summary = "사용자 목록 조회")
    public ApiResponse<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.page(userRepository.findAll(PageRequest.of(page, size)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "사용자 생성")
    public ApiResponse<UserEntity> create(@Valid @RequestBody UserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Email already in use: " + request.email());
        }
        UserEntity entity = new UserEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setEmail(request.email());
        entity.setName(request.name());
        entity.setRoleId(request.roleId());
        entity.setActive(true);
        UserEntity saved = userRepository.save(entity);

        // CR-027: Master DB user_tenant_map에 매핑 등록
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            masterJdbcTemplate.update(
                "INSERT INTO user_tenant_map (email, tenant_id) VALUES (?, ?) ON CONFLICT (email) DO NOTHING",
                request.email(), tenantId
            );
        }

        return ApiResponse.ok(saved);
    }

    @GetMapping("/{id}")
    @Operation(summary = "사용자 상세 조회")
    public ApiResponse<UserEntity> get(@PathVariable String id) {
        return userRepository.findById(id)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "User not found: " + id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "사용자 수정")
    public ApiResponse<UserEntity> update(@PathVariable String id,
                                           @Valid @RequestBody UserRequest request) {
        UserEntity entity = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "User not found: " + id));
        entity.setName(request.name());
        entity.setRoleId(request.roleId());
        return ApiResponse.ok(userRepository.save(entity));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "사용자 비활성화")
    public void delete(@PathVariable String id) {
        UserEntity entity = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "User not found: " + id));
        entity.setActive(false);
        userRepository.save(entity);

        // CR-027: Master DB user_tenant_map에서 매핑 삭제
        masterJdbcTemplate.update(
            "DELETE FROM user_tenant_map WHERE email = ?", entity.getEmail()
        );
    }

    @PatchMapping("/{id}/activate")
    @Operation(summary = "사용자 활성화/비활성화")
    public ApiResponse<UserEntity> setActive(@PathVariable String id,
                                              @RequestParam boolean active) {
        UserEntity entity = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "User not found: " + id));
        entity.setActive(active);
        return ApiResponse.ok(userRepository.save(entity));
    }

    @PostMapping("/{id}/api-key")
    @Operation(summary = "API 키 재발급")
    public ApiResponse<java.util.Map<String, String>> regenerateApiKey(@PathVariable String id) {
        UserEntity entity = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "User not found: " + id));
        String rawKey = "plat-" + UUID.randomUUID().toString().replace("-", "");
        entity.setApiKeyHash(sha256(rawKey));
        userRepository.save(entity);
        return ApiResponse.ok(java.util.Map.of("apiKey", rawKey,
                "note", "이 키는 다시 조회할 수 없습니다. 안전한 곳에 저장하세요."));
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

    public record UserRequest(
            @Email @NotBlank String email,
            String name,
            String roleId
    ) {}
}
