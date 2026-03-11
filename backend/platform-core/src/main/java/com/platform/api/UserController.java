package com.platform.api;

import com.platform.domain.UserEntity;
import com.platform.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "사용자 관리")
public class UserController {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
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
        return ApiResponse.ok(userRepository.save(entity));
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
    }

    @PostMapping("/{id}/api-key")
    @Operation(summary = "API 키 재발급")
    public ApiResponse<java.util.Map<String, String>> regenerateApiKey(@PathVariable String id) {
        UserEntity entity = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "User not found: " + id));
        String rawKey = "plat-" + UUID.randomUUID().toString().replace("-", "");
        entity.setApiKeyHash(passwordEncoder.encode(rawKey));
        userRepository.save(entity);
        return ApiResponse.ok(java.util.Map.of("apiKey", rawKey,
                "note", "이 키는 다시 조회할 수 없습니다. 안전한 곳에 저장하세요."));
    }

    public record UserRequest(
            @Email @NotBlank String email,
            String name,
            String roleId
    ) {}
}
