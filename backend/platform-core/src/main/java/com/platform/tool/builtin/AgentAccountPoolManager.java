package com.platform.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.master.AgentAccountAssignmentEntity;
import com.platform.domain.master.AgentAccountEntity;
import com.platform.orchestrator.GenericCircuitBreaker;
import com.platform.repository.master.AgentAccountAssignmentRepository;
import com.platform.repository.master.AgentAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 에이전트 계정 풀 매니저.
 * 다수의 사이드카 컨테이너(각각 독립 OAuth/API Key)를 관리하고,
 * 테넌트/앱 컨텍스트에 따라 적절한 계정을 해소한다.
 */
@Service
@ConditionalOnProperty(name = "claude-code.enabled", havingValue = "true", matchIfMissing = false)
public class AgentAccountPoolManager {

    private static final Logger log = LoggerFactory.getLogger(AgentAccountPoolManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final int CB_FAILURE_THRESHOLD = 3;
    private static final long CB_OPEN_DURATION_MS = 5 * 60 * 1000L;

    private final AgentAccountRepository accountRepository;
    private final AgentAccountAssignmentRepository assignmentRepository;
    private final HttpClient httpClient;

    /** 계정별 서킷 브레이커 */
    private final ConcurrentHashMap<String, GenericCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    /** 계정별 현재 동시 실행 수 */
    private final ConcurrentHashMap<String, AtomicInteger> concurrencyCounters = new ConcurrentHashMap<>();

    /** 라운드로빈 카운터 */
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    public AgentAccountPoolManager(AgentAccountRepository accountRepository,
                                   AgentAccountAssignmentRepository assignmentRepository) {
        this.accountRepository = accountRepository;
        this.assignmentRepository = assignmentRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("AgentAccountPoolManager 초기화 완료");
    }

    /**
     * 테넌트/앱 컨텍스트로 사용할 에이전트 계정을 해소한다.
     *
     * 우선순위:
     * 1. (tenantId, appId) — 가장 구체적
     * 2. (tenantId, null) — 테넌트 레벨
     * 3. (null, appId) — 앱 레벨
     * 4. round_robin 타입 중 가용 계정
     * 5. null → 로컬 ProcessBuilder 경로
     */
    public AgentAccountEntity resolveAccount(String agentType, String tenantId, String appId) {
        // 1~3: 할당 기반 해소 (JPQL이 구체성 순 정렬)
        List<AgentAccountAssignmentEntity> assignments =
                assignmentRepository.findActiveAssignments(agentType, tenantId, appId);

        for (AgentAccountAssignmentEntity assignment : assignments) {
            AgentAccountEntity account = assignment.getAccount();
            if (isAvailable(account)) {
                log.debug("계정 해소: tenant={}, app={} → account={}", tenantId, appId, account.getId());
                return account;
            }
        }

        // 4: 라운드로빈 폴백
        List<AgentAccountAssignmentEntity> roundRobins =
                assignmentRepository.findRoundRobinAssignments(agentType);
        List<AgentAccountEntity> available = roundRobins.stream()
                .map(AgentAccountAssignmentEntity::getAccount)
                .filter(this::isAvailable)
                .toList();

        if (!available.isEmpty()) {
            int idx = Math.abs(roundRobinCounter.getAndIncrement()) % available.size();
            AgentAccountEntity account = available.get(idx);
            log.debug("라운드로빈 해소: → account={}", account.getId());
            return account;
        }

        // 5: null → 로컬 경로
        log.debug("풀에서 가용 계정 없음, 로컬 경로 사용");
        return null;
    }

    /** ID로 특정 계정 조회 */
    public AgentAccountEntity getAccount(String accountId) {
        return accountRepository.findById(accountId).orElse(null);
    }

    /**
     * 사이드카 HTTP를 통해 커맨드 실행.
     */
    public ExecutionResult executeViaHttp(AgentAccountEntity account,
                                          List<String> command,
                                          String workDir,
                                          int timeoutSeconds,
                                          String inputFile) {
        String accountId = account.getId();
        AtomicInteger counter = concurrencyCounters.computeIfAbsent(accountId, k -> new AtomicInteger(0));
        counter.incrementAndGet();

        try {
            String url = account.getBaseUrl() + "/execute";
            Map<String, Object> body = Map.of(
                    "command", command,
                    "workingDirectory", workDir != null ? workDir : "/data/workspace",
                    "timeoutSeconds", timeoutSeconds,
                    "inputFile", inputFile != null ? inputFile : ""
            );

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds + 30)) // 사이드카 타임아웃 + 여유
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            log.info("[{}] 사이드카 실행 요청: host={}", accountId, account.getContainerHost());

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

            int exitCode = result.get("exitCode") instanceof Number n ? n.intValue() : -1;
            String stdout = (String) result.getOrDefault("stdout", "");
            String stderr = (String) result.getOrDefault("stderr", "");

            return new ExecutionResult(exitCode, stdout, stderr);

        } catch (Exception e) {
            log.error("[{}] 사이드카 통신 실패: {}", accountId, e.getMessage());
            return new ExecutionResult(-1, "", "Sidecar communication failed: " + e.getMessage());
        } finally {
            counter.decrementAndGet();
        }
    }

    /** 성공 기록 */
    public void recordSuccess(String accountId) {
        getCircuitBreaker(accountId).recordSuccess();
    }

    /** 실패 기록 */
    public void recordFailure(String accountId) {
        getCircuitBreaker(accountId).recordFailure();
    }

    /** 서킷 브레이커 상태 조회 */
    public GenericCircuitBreaker getCircuitBreaker(String accountId) {
        return circuitBreakers.computeIfAbsent(accountId,
                id -> new GenericCircuitBreaker("agent-account:" + id, CB_FAILURE_THRESHOLD, CB_OPEN_DURATION_MS));
    }

    /** 서킷 브레이커 리셋 */
    public void resetCircuitBreaker(String accountId) {
        GenericCircuitBreaker cb = circuitBreakers.get(accountId);
        if (cb != null) cb.reset();
    }

    /** 30초 주기 헬스체크 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void healthCheckAll() {
        List<AgentAccountEntity> accounts = accountRepository.findByStatusOrderByPriorityDesc("active");
        for (AgentAccountEntity account : accounts) {
            checkHealth(account);
        }
    }

    private void checkHealth(AgentAccountEntity account) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(account.getBaseUrl() + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // 사이드카 응답에서 인증 상태 확인
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> healthResult = objectMapper.readValue(response.body(), Map.class);
                    String status = (String) healthResult.getOrDefault("status", "ok");
                    if ("auth_required".equals(status)) {
                        if (!"auth_required".equals(account.getHealthStatus())) {
                            log.warn("[{}] 인증 필요 — claude login을 실행하세요", account.getId());
                        }
                        account.setHealthStatus("auth_required");
                    } else {
                        if (!"healthy".equals(account.getHealthStatus())) {
                            log.info("[{}] 헬스 → healthy", account.getId());
                        }
                        account.setHealthStatus("healthy");
                    }
                } catch (Exception parseEx) {
                    account.setHealthStatus("healthy");
                }
            } else {
                log.warn("[{}] 헬스체크 실패: status={}", account.getId(), response.statusCode());
                account.setHealthStatus("unhealthy");
            }
        } catch (Exception e) {
            log.warn("[{}] 헬스체크 오류: {}", account.getId(), e.getMessage());
            account.setHealthStatus("unhealthy");
        }

        account.setLastHealthAt(OffsetDateTime.now());
        accountRepository.save(account);
    }

    /** 풀 상태 조회 (Admin API용) */
    public List<AccountPoolStatus> getPoolStatus() {
        return accountRepository.findAll().stream()
                .map(account -> new AccountPoolStatus(
                        account.getId(),
                        account.getName(),
                        account.getAgentType(),
                        account.getStatus(),
                        account.getHealthStatus(),
                        getCircuitBreaker(account.getId()).getState().name(),
                        getCurrentConcurrency(account.getId()),
                        account.getMaxConcurrent(),
                        account.getLastHealthAt()
                ))
                .toList();
    }

    private boolean isAvailable(AgentAccountEntity account) {
        // 서킷 브레이커 체크
        if (!getCircuitBreaker(account.getId()).allowRequest()) {
            return false;
        }
        // 헬스 체크
        if ("unhealthy".equals(account.getHealthStatus())) {
            return false;
        }
        // 동시실행 제한
        if (getCurrentConcurrency(account.getId()) >= account.getMaxConcurrent()) {
            return false;
        }
        return true;
    }

    private int getCurrentConcurrency(String accountId) {
        AtomicInteger counter = concurrencyCounters.get(accountId);
        return counter != null ? counter.get() : 0;
    }

    // ── 토큰 관리 ──

    /**
     * 사이드카에서 OAuth 토큰을 추출하여 DB에 저장.
     * 사이드카 재빌드 후 deployToken()으로 복원할 수 있다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> extractAndSaveToken(String accountId) {
        AgentAccountEntity account = accountRepository.findById(accountId).orElse(null);
        if (account == null) throw new IllegalArgumentException("계정 없음: " + accountId);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(account.getBaseUrl() + "/extract-token"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

            if (!"ok".equals(result.get("status"))) {
                throw new RuntimeException("토큰 추출 실패: " + result.get("error"));
            }

            // config JSONB에 토큰 저장
            Map<String, Object> config = account.getConfig() != null
                    ? new java.util.HashMap<>(account.getConfig()) : new java.util.HashMap<>();
            config.put("oauth_token", result.get("token_json"));
            config.put("oauth_credentials", result.get("credentials"));
            config.put("token_saved_at", OffsetDateTime.now().toString());
            account.setConfig(config);
            accountRepository.save(account);

            log.info("[{}] OAuth 토큰 추출 및 DB 저장 완료", accountId);
            return Map.of("status", "ok", "message", "토큰 저장 완료", "saved_at", config.get("token_saved_at"));

        } catch (Exception e) {
            log.error("[{}] 토큰 추출 실패: {}", accountId, e.getMessage());
            throw new RuntimeException("토큰 추출 실패: " + e.getMessage(), e);
        }
    }

    /**
     * DB에 저장된 OAuth 토큰을 사이드카에 배포.
     * 사이드카 재빌드/재기동 후 호출하면 인증이 복원된다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> deployToken(String accountId) {
        AgentAccountEntity account = accountRepository.findById(accountId).orElse(null);
        if (account == null) throw new IllegalArgumentException("계정 없음: " + accountId);

        Map<String, Object> config = account.getConfig();
        if (config == null || !config.containsKey("oauth_token")) {
            throw new IllegalStateException("저장된 토큰 없음. 먼저 extractAndSaveToken을 실행하세요.");
        }

        try {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("token_json", config.get("oauth_token"));
            body.put("credentials", config.get("oauth_credentials"));

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(account.getBaseUrl() + "/upload-token"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

            log.info("[{}] OAuth 토큰 배포 완료", accountId);
            return Map.of("status", "ok", "message", "토큰 배포 완료");

        } catch (Exception e) {
            log.error("[{}] 토큰 배포 실패: {}", accountId, e.getMessage());
            throw new RuntimeException("토큰 배포 실패: " + e.getMessage(), e);
        }
    }

    // ── 내부 레코드 ──

    public record ExecutionResult(int exitCode, String stdout, String stderr) {}

    public record AccountPoolStatus(
            String accountId,
            String name,
            String agentType,
            String status,
            String healthStatus,
            String circuitState,
            int currentConcurrency,
            int maxConcurrent,
            OffsetDateTime lastHealthAt
    ) {}
}
