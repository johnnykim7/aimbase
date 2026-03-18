package com.platform.api;

import com.platform.auth.JwtProvider;
import com.platform.domain.UserEntity;
import com.platform.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "인증 (로그인, 토큰 갱신)")
public class AuthController {

    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserRepository userRepository,
                          JwtProvider jwtProvider,
                          PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.jwtProvider = jwtProvider;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    @Operation(summary = "로그인 (이메일 + 비밀번호)")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        UserEntity user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!user.isActive()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is disabled");
        }

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        String role = user.getRoleId() != null ? user.getRoleId() : "viewer";
        // tenantId는 TenantResolver에서 X-Tenant-Id 헤더로 결정됨 — 토큰에는 빈 문자열 대체
        String tenantId = "";
        String accessToken = jwtProvider.generateAccessToken(user.getId(), user.getEmail(), tenantId, role);
        String refreshToken = jwtProvider.generateRefreshToken(user.getId());

        // Refresh token 해시 저장
        user.setRefreshTokenHash(sha256(refreshToken));
        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);

        return ApiResponse.ok(Map.of(
                "access_token", accessToken,
                "refresh_token", refreshToken,
                "token_type", "Bearer",
                "user_id", user.getId(),
                "email", user.getEmail(),
                "role", role
        ));
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
        String tenantId = "";
        String accessToken = jwtProvider.generateAccessToken(user.getId(), user.getEmail(), tenantId, role);

        return ApiResponse.ok(Map.of(
                "access_token", accessToken,
                "token_type", "Bearer"
        ));
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
