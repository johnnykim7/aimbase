package com.platform.api;

import com.platform.domain.master.AgentAccountAssignmentEntity;
import com.platform.domain.master.AgentAccountEntity;
import com.platform.repository.master.AgentAccountAssignmentRepository;
import com.platform.repository.master.AgentAccountRepository;
import com.platform.tool.builtin.AgentAccountPoolManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 에이전트 계정 풀 관리 API (Platform/SuperAdmin 전용, Master DB).
 */
@RestController
@RequestMapping("/api/v1/platform/agent-accounts")
@Tag(name = "Agent Account Pool", description = "에이전트 계정 풀 관리 (OAuth/API Key 계정, 사이드카)")
@ConditionalOnProperty(name = "claude-code.enabled", havingValue = "true", matchIfMissing = false)
public class AgentAccountController {

    private final AgentAccountRepository accountRepository;
    private final AgentAccountAssignmentRepository assignmentRepository;
    private final AgentAccountPoolManager poolManager;

    public AgentAccountController(AgentAccountRepository accountRepository,
                                  AgentAccountAssignmentRepository assignmentRepository,
                                  AgentAccountPoolManager poolManager) {
        this.accountRepository = accountRepository;
        this.assignmentRepository = assignmentRepository;
        this.poolManager = poolManager;
    }

    // ── 계정 CRUD ──

    @GetMapping
    @Operation(summary = "전체 계정 + 풀 상태 조회")
    public List<AgentAccountPoolManager.AccountPoolStatus> listAccounts() {
        return poolManager.getPoolStatus();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "계정 등록")
    public AgentAccountEntity createAccount(@RequestBody AgentAccountEntity entity) {
        if (entity.getId() == null || entity.getId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id는 필수입니다");
        }
        if (accountRepository.existsById(entity.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 존재하는 ID: " + entity.getId());
        }
        return accountRepository.save(entity);
    }

    @GetMapping("/{id}")
    @Operation(summary = "계정 상세 조회")
    public AgentAccountEntity getAccount(@PathVariable String id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "계정 없음: " + id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "계정 수정")
    public AgentAccountEntity updateAccount(@PathVariable String id, @RequestBody AgentAccountEntity update) {
        AgentAccountEntity existing = accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "계정 없음: " + id));

        if (update.getName() != null) existing.setName(update.getName());
        if (update.getAgentType() != null) existing.setAgentType(update.getAgentType());
        if (update.getAuthType() != null) existing.setAuthType(update.getAuthType());
        if (update.getContainerHost() != null) existing.setContainerHost(update.getContainerHost());
        if (update.getContainerPort() != null) existing.setContainerPort(update.getContainerPort());
        if (update.getStatus() != null) existing.setStatus(update.getStatus());
        if (update.getPriority() != null) existing.setPriority(update.getPriority());
        if (update.getMaxConcurrent() != null) existing.setMaxConcurrent(update.getMaxConcurrent());
        if (update.getConfig() != null) existing.setConfig(update.getConfig());

        return accountRepository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "계정 삭제")
    public void deleteAccount(@PathVariable String id) {
        if (!accountRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "계정 없음: " + id);
        }
        accountRepository.deleteById(id);
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "사이드카 연결 테스트")
    public Map<String, Object> testAccount(@PathVariable String id) {
        AgentAccountEntity account = accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "계정 없음: " + id));

        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5)).build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(account.getBaseUrl() + "/health"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET().build();
            java.net.http.HttpResponse<String> response =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            return Map.of("status", "ok", "response", response.body());
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @PostMapping("/{id}/upload-token")
    @Operation(summary = "OAuth 토큰 업로드 — 사이드카에 .claude.json 배포")
    public Map<String, Object> uploadToken(@PathVariable String id, @RequestBody Map<String, Object> body) {
        AgentAccountEntity account = accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "계정 없음: " + id));

        Object tokenJson = body.get("token_json");
        if (tokenJson == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "token_json은 필수입니다");
        }

        try {
            var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String jsonBody = objectMapper.writeValueAsString(body);

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10)).build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(account.getBaseUrl() + "/upload-token"))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(10))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            java.net.http.HttpResponse<String> response =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return Map.of("status", "ok", "account_id", id, "message", "토큰 배포 완료");
            } else {
                return Map.of("status", "error", "account_id", id, "response", response.body());
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "사이드카 통신 실패: " + e.getMessage());
        }
    }

    @PostMapping("/{id}/extract-token")
    @Operation(summary = "OAuth 토큰 추출 — 사이드카에서 추출하여 DB에 저장 (재빌드 후 자동 복원)")
    public Map<String, Object> extractToken(@PathVariable String id) {
        return poolManager.extractAndSaveToken(id);
    }

    @PostMapping("/{id}/deploy-token")
    @Operation(summary = "OAuth 토큰 배포 — DB에 저장된 토큰을 사이드카에 수동 배포")
    public Map<String, Object> deployToken(@PathVariable String id) {
        return poolManager.deployToken(id);
    }

    @GetMapping("/{id}/token")
    @Operation(summary = "인증 토큰 조회 — 사이드카 기동 시 자동으로 호출")
    public Map<String, Object> getToken(@PathVariable String id) {
        AgentAccountEntity account = accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "계정 없음: " + id));

        Map<String, Object> config = account.getConfig();
        if (config == null || !config.containsKey("auth_token")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "저장된 토큰 없음. UI에서 토큰을 등록하세요.");
        }

        return Map.of(
                "auth_type", config.getOrDefault("auth_type", "oauth_token"),
                "auth_token", config.get("auth_token")
        );
    }

    @PostMapping("/{id}/save-token")
    @Operation(summary = "인증 토큰 저장 — UI에서 setup-token 또는 API Key를 입력하여 DB에 저장")
    public Map<String, Object> saveToken(@PathVariable String id, @RequestBody Map<String, Object> body) {
        AgentAccountEntity account = accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "계정 없음: " + id));

        String authType = (String) body.get("auth_type"); // oauth_token 또는 api_key
        String authToken = (String) body.get("auth_token");
        if (authToken == null || authToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "auth_token은 필수입니다");
        }
        if (authType == null || authType.isBlank()) {
            authType = authToken.startsWith("sk-ant-oat") ? "oauth_token" : "api_key";
        }

        Map<String, Object> config = account.getConfig() != null
                ? new java.util.HashMap<>(account.getConfig()) : new java.util.HashMap<>();
        config.put("auth_type", authType);
        config.put("auth_token", authToken);
        config.put("token_saved_at", java.time.OffsetDateTime.now().toString());
        account.setConfig(config);
        accountRepository.save(account);

        return Map.of("status", "ok", "auth_type", authType, "message", "토큰 저장 완료");
    }

    @PostMapping("/{id}/reset-circuit")
    @Operation(summary = "서킷 브레이커 리셋")
    public Map<String, Object> resetCircuit(@PathVariable String id) {
        poolManager.resetCircuitBreaker(id);
        return Map.of("account_id", id, "circuit_state", "CLOSED", "message", "서킷 리셋 완료");
    }

    // ── 할당 관리 ──

    @GetMapping("/assignments")
    @Operation(summary = "할당 목록 조회")
    public List<AgentAccountAssignmentEntity> listAssignments() {
        return assignmentRepository.findAll();
    }

    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "할당 생성")
    public AgentAccountAssignmentEntity createAssignment(@RequestBody Map<String, Object> body) {
        String accountId = (String) body.get("account_id");
        if (accountId == null || accountId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "account_id는 필수입니다");
        }

        AgentAccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "계정 없음: " + accountId));

        AgentAccountAssignmentEntity assignment = new AgentAccountAssignmentEntity();
        assignment.setAccount(account);
        assignment.setTenantId((String) body.get("tenant_id"));
        assignment.setAppId((String) body.get("app_id"));
        assignment.setAssignmentType((String) body.getOrDefault("assignment_type", "fixed"));
        if (body.containsKey("priority")) {
            assignment.setPriority(((Number) body.get("priority")).intValue());
        }

        return assignmentRepository.save(assignment);
    }

    @DeleteMapping("/assignments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "할당 삭제")
    public void deleteAssignment(@PathVariable Long id) {
        if (!assignmentRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "할당 없음: " + id);
        }
        assignmentRepository.deleteById(id);
    }
}
