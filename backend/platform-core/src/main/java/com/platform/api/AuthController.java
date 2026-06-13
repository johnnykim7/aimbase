package com.platform.api;

import com.platform.auth.JwtProvider;
import com.platform.domain.UserEntity;
import com.platform.domain.master.TenantAdminEntity;
import com.platform.repository.UserRepository;
import com.platform.repository.master.TenantAdminRepository;
import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "인증 (로그인, 토큰 갱신)")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate masterJdbcTemplate;
    private final TenantDataSourceManager dataSourceManager;
    private final TenantAdminRepository tenantAdminRepository;

    public AuthController(UserRepository userRepository,
                          JwtProvider jwtProvider,
                          PasswordEncoder passwordEncoder,
                          @Qualifier("masterJdbcTemplate") JdbcTemplate masterJdbcTemplate,
                          TenantDataSourceManager dataSourceManager,
                          TenantAdminRepository tenantAdminRepository) {
        this.userRepository = userRepository;
        this.jwtProvider = jwtProvider;
        this.passwordEncoder = passwordEncoder;
        this.masterJdbcTemplate = masterJdbcTemplate;
        this.dataSourceManager = dataSourceManager;
        this.tenantAdminRepository = tenantAdminRepository;
    }

    @PostMapping("/login")
    @Operation(summary = "로그인 (이메일 + 비밀번호) — X-Tenant-Id 미전달 시 자동 resolve [CR-027]")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        // CR-096: Master tenant_admins(플랫폼 전역 super admin) 로그인 분기.
        // tenant_admins 에 이메일이 있으면 이 경로로 인증 — 어느 테넌트에도 묶이지 않는다(tenant_id=null).
        // 발급 토큰의 role 로 테넌트 임퍼소네이션 API(/platform/tenants/{id}/impersonate)를 호출한다.
        Optional<TenantAdminEntity> adminOpt = tenantAdminRepository.findByEmail(request.email());
        if (adminOpt.isPresent()) {
            return loginAsPlatformAdmin(adminOpt.get(), request);
        }

        // CR-027: X-Tenant-Id가 없으면 Master DB에서 email로 tenant_id 자동 resolve
        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = resolveTenantByEmail(request.email());
            if (tenantId == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
            }
            // 동적으로 TenantContext 설정
            TenantContext.setTenantId(tenantId);
        }

        UserEntity user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is disabled");
        }

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        String role = user.getRoleId() != null ? user.getRoleId() : "viewer";
        String accessToken = jwtProvider.generateAccessToken(user.getId(), user.getEmail(), tenantId, role);
        String refreshToken = jwtProvider.generateRefreshToken(user.getId());

        // Refresh token 해시 저장
        user.setRefreshTokenHash(sha256(refreshToken));
        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);

        // CR-027: 응답에 tenant_id 포함
        Map<String, Object> response = new HashMap<>();
        response.put("access_token", accessToken);
        response.put("refresh_token", refreshToken);
        response.put("token_type", "Bearer");
        response.put("user_id", user.getId());
        response.put("email", user.getEmail());
        response.put("role", role);
        response.put("tenant_id", tenantId);

        return ApiResponse.ok(response);
    }

    /**
     * CR-096: Master tenant_admins 기반 플랫폼 전역 super admin 로그인.
     * tenant_id 가 null 인 access 토큰을 발급한다 — platform API(/api/v1/platform/**)는
     * JwtAuthenticationFilter:104 가 TenantContext 없이 claim 만으로 인증하므로 동작한다.
     * 임퍼소네이션 토큰은 별도 API(/platform/tenants/{id}/impersonate)에서 발급한다.
     */
    private ApiResponse<Map<String, Object>> loginAsPlatformAdmin(TenantAdminEntity admin, LoginRequest request) {
        if (admin.getIsActive() == null || !admin.getIsActive()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is disabled");
        }
        if (admin.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), admin.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        String role = admin.getRole() != null ? admin.getRole() : "super_admin";
        // 전역 admin 은 어느 테넌트에도 묶이지 않는다 → tenant_id=null
        String accessToken = jwtProvider.generateAccessToken(
                admin.getId().toString(), admin.getEmail(), null, role);

        admin.setLastLoginAt(OffsetDateTime.now());
        tenantAdminRepository.save(admin);

        Map<String, Object> response = new HashMap<>();
        response.put("access_token", accessToken);
        response.put("token_type", "Bearer");
        response.put("user_id", admin.getId().toString());
        response.put("email", admin.getEmail());
        response.put("role", role);
        response.put("tenant_id", null);
        response.put("is_platform_admin", true);
        log.info("Platform admin login: {} (role={})", admin.getEmail(), role);
        return ApiResponse.ok(response);
    }

    @PostMapping("/logout")
    @Operation(summary = "로그아웃 — Refresh Token 무효화")
    public ApiResponse<Map<String, String>> logout() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }

        String userId = auth.getName();
        userRepository.findById(userId).ifPresent(user -> {
            user.setRefreshTokenHash(null);
            userRepository.save(user);
        });

        return ApiResponse.ok(Map.of("message", "Logged out successfully"));
    }

    @PostMapping("/refresh")
    @Operation(summary = "토큰 갱신 (Refresh Token → 새 Access Token)")
    public ApiResponse<Map<String, Object>> refresh(@Valid @RequestBody RefreshRequest request) {
        if (!jwtProvider.validateToken(request.refreshToken())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
        }

        String tokenType = jwtProvider.getTokenType(request.refreshToken());
        if (!"refresh".equals(tokenType)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not a refresh token");
        }

        String userId = jwtProvider.getUserId(request.refreshToken());
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is disabled");
        }

        // Refresh token 해시 검증
        String hash = sha256(request.refreshToken());
        if (user.getRefreshTokenHash() == null || !user.getRefreshTokenHash().equals(hash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token revoked");
        }

        String role = user.getRoleId() != null ? user.getRoleId() : "viewer";
        String tenantId = TenantContext.getTenantId();
        String accessToken = jwtProvider.generateAccessToken(user.getId(), user.getEmail(), tenantId, role);

        return ApiResponse.ok(Map.of(
                "access_token", accessToken,
                "token_type", "Bearer"
        ));
    }

    /**
     * CR-027: Master DB user_tenant_map에서 email로 tenant_id 조회.
     */
    private String resolveTenantByEmail(String email) {
        try {
            List<Map<String, Object>> rows = masterJdbcTemplate.queryForList(
                "SELECT tenant_id FROM user_tenant_map WHERE email = ?", email
            );
            if (rows.isEmpty()) {
                log.debug("No tenant mapping found for email: {}", email);
                return null;
            }
            String tenantId = (String) rows.get(0).get("tenant_id");
            log.info("Resolved tenant '{}' for email '{}'", tenantId, email);
            return tenantId;
        } catch (Exception e) {
            log.warn("Failed to resolve tenant for email '{}': {}", email, e.getMessage());
            return null;
        }
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

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    public record RefreshRequest(
            @NotBlank String refreshToken
    ) {}
}
