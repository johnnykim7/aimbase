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

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 에이전트 계정 풀 매니저.
 * CLAUDE_CONFIG_DIR 환경변수로 계정별 설정 디렉토리를 격리하여
 * 단일 프로세스 내에서 다중 OAuth/API Key 계정을 관리한다.
 *
 * 기존 HTTP 사이드카 방식을 제거하고 로컬 ProcessBuilder로 전환.
 */
@Service
@ConditionalOnProperty(name = "claude-code.enabled", havingValue = "true", matchIfMissing = false)
public class AgentAccountPoolManager {

    private static final Logger log = LoggerFactory.getLogger(AgentAccountPoolManager.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final int CB_FAILURE_THRESHOLD = 3;
    private static final long CB_OPEN_DURATION_MS = 5 * 60 * 1000L;

    /** 계정별 Claude 설정 디렉토리 루트 */
    private static final String CONFIG_BASE_DIR = System.getProperty("user.home") + "/.claude-accounts";

    private final AgentAccountRepository accountRepository;
    private final AgentAccountAssignmentRepository assignmentRepository;
    private final ClaudeCodeToolConfig config;

    /** 계정별 서킷 브레이커 */
    private final ConcurrentHashMap<String, GenericCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    /** 계정별 현재 동시 실행 수 */
    private final ConcurrentHashMap<String, AtomicInteger> concurrencyCounters = new ConcurrentHashMap<>();

    /** 라운드로빈 카운터 */
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    public AgentAccountPoolManager(AgentAccountRepository accountRepository,
                                   AgentAccountAssignmentRepository assignmentRepository,
                                   ClaudeCodeToolConfig config) {
        this.accountRepository = accountRepository;
        this.assignmentRepository = assignmentRepository;
        this.config = config;
        log.info("AgentAccountPoolManager 초기화: configBaseDir={}", CONFIG_BASE_DIR);
    }

    /**
     * 앱 기동 시 DB에 저장된 토큰을 계정별 config 디렉토리에 배포.
     */
    @PostConstruct
    public void initializeAccountConfigs() {
        try {
            Files.createDirectories(Path.of(CONFIG_BASE_DIR));
            List<AgentAccountEntity> accounts = accountRepository.findByStatusOrderByPriorityDesc("active");
            int deployed = 0;
            for (AgentAccountEntity account : accounts) {
                try {
                    if (deployTokenToConfigDir(account)) {
                        deployed++;
                    }
                } catch (Exception e) {
                    log.warn("[{}] 토큰 배포 실패 (기동 계속): {}", account.getId(), e.getMessage());
                }
            }
            log.info("계정 토큰 초기화 완료: {}/{}개 배포", deployed, accounts.size());
        } catch (Exception e) {
            log.error("계정 config 디렉토리 초기화 실패: {}", e.getMessage());
        }
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
     * 로컬 ProcessBuilder로 커맨드 실행 (CLAUDE_CONFIG_DIR로 계정 격리).
     */
    public ExecutionResult executeLocal(AgentAccountEntity account,
                                        List<String> command,
                                        String workDir,
                                        int timeoutSeconds,
                                        String inputFile) {
        String accountId = account.getId();
        AtomicInteger counter = concurrencyCounters.computeIfAbsent(accountId, k -> new AtomicInteger(0));
        counter.incrementAndGet();

        try {
            ProcessBuilder pb = new ProcessBuilder(command);

            // 계정별 CLAUDE_CONFIG_DIR 설정 — 핵심 격리 메커니즘
            String configDir = getConfigDir(accountId);
            pb.environment().put("CLAUDE_CONFIG_DIR", configDir);

            // 중첩 세션 방지
            pb.environment().remove("CLAUDECODE");

            // 인증 방식별 환경변수 설정
            Map<String, Object> accountConfig = account.getConfig();
            if (accountConfig != null) {
                String authType = (String) accountConfig.get("auth_type");
                String authToken = (String) accountConfig.get("auth_token");
                if ("api_key".equals(authType) && authToken != null) {
                    pb.environment().put("ANTHROPIC_API_KEY", authToken);
                    if (!command.contains("--bare")) {
                        command = new java.util.ArrayList<>(command);
                        command.add(1, "--bare");
                    }
                } else if ("oauth_token".equals(authType) && authToken != null) {
                    pb.environment().put("CLAUDE_CODE_OAUTH_TOKEN", authToken);
                }
            }

            if (workDir != null && !workDir.isBlank()) {
                File dir = new File(workDir);
                if (dir.isDirectory()) {
                    pb.directory(dir);
                }
            }
            pb.redirectErrorStream(false);

            if (inputFile != null && !inputFile.isBlank()) {
                File inFile = new File(inputFile);
                if (inFile.exists()) {
                    pb.redirectInput(inFile);
                }
            } else {
                pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            }

            log.info("[{}] 로컬 실행: configDir={}", accountId, configDir);

            Process process = pb.start();

            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                    () -> readStream(process.getInputStream()));
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                    () -> readStream(process.getErrorStream()));

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ExecutionResult(-1, "", "Process killed: timeout exceeded (" + timeoutSeconds + "s)");
            }

            String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(5, TimeUnit.SECONDS);
            return new ExecutionResult(process.exitValue(), stdout, stderr);

        } catch (Exception e) {
            log.error("[{}] 로컬 실행 실패: {}", accountId, e.getMessage());
            return new ExecutionResult(-1, "", "Local execution failed: " + e.getMessage());
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

    /**
     * 60초 주기 헬스체크 — CLI 버전 확인 + 인증 상태 확인.
     * CLAUDE_CONFIG_DIR별로 `claude --version`을 실행하여 CLI 정상 여부 판단.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void healthCheckAll() {
        List<AgentAccountEntity> accounts = accountRepository.findByStatusOrderByPriorityDesc("active");
        for (AgentAccountEntity account : accounts) {
            checkHealth(account);
        }
    }

    private void checkHealth(AgentAccountEntity account) {
        String configDir = getConfigDir(account.getId());
        Path configPath = Path.of(configDir);

        // config 디렉토리 존재 여부 확인
        if (!Files.exists(configPath)) {
            account.setHealthStatus("not_configured");
            account.setLastHealthAt(OffsetDateTime.now());
            accountRepository.save(account);
            return;
        }

        // 토큰 파일 존재 확인 (credentials.json 또는 .claude.json)
        boolean hasCredentials = Files.exists(configPath.resolve("credentials.json"))
                || Files.exists(configPath.resolve(".credentials.json"))
                || Files.exists(configPath.resolve(".claude.json"));
        Map<String, Object> accountConfig = account.getConfig();
        boolean hasApiKey = accountConfig != null
                && "api_key".equals(accountConfig.get("auth_type"))
                && accountConfig.get("auth_token") != null;

        if (!hasCredentials && !hasApiKey) {
            if (!"auth_required".equals(account.getHealthStatus())) {
                log.warn("[{}] 인증 필요 — 토큰을 등록하세요", account.getId());
            }
            account.setHealthStatus("auth_required");
        } else {
            if (!"healthy".equals(account.getHealthStatus())) {
                log.info("[{}] 헬스 → healthy", account.getId());
            }
            account.setHealthStatus("healthy");
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
        if (!getCircuitBreaker(account.getId()).allowRequest()) {
            return false;
        }
        String health = account.getHealthStatus();
        if ("unhealthy".equals(health) || "not_configured".equals(health)) {
            return false;
        }
        if (getCurrentConcurrency(account.getId()) >= account.getMaxConcurrent()) {
            return false;
        }
        return true;
    }

    private int getCurrentConcurrency(String accountId) {
        AtomicInteger counter = concurrencyCounters.get(accountId);
        return counter != null ? counter.get() : 0;
    }

    // ── 토큰 관리 (로컬 파일시스템) ──

    /** 계정별 config 디렉토리 경로 */
    public String getConfigDir(String accountId) {
        return CONFIG_BASE_DIR + "/" + accountId;
    }

    /**
     * 계정의 config 디렉토리에서 credentials를 읽어 DB에 저장.
     * 컨테이너 재빌드 후 deployToken()으로 복원.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> extractAndSaveToken(String accountId) {
        AgentAccountEntity account = accountRepository.findById(accountId).orElse(null);
        if (account == null) throw new IllegalArgumentException("계정 없음: " + accountId);

        try {
            Path configDir = Path.of(getConfigDir(accountId));
            Path credentialsFile = configDir.resolve("credentials.json");
            if (!Files.exists(credentialsFile)) {
                credentialsFile = configDir.resolve(".credentials.json");
            }
            if (!Files.exists(credentialsFile)) {
                throw new IllegalStateException("credentials 파일 없음. claude login을 먼저 실행하세요.");
            }

            String credentialsJson = Files.readString(credentialsFile, StandardCharsets.UTF_8);

            // config JSONB에 토큰 저장
            Map<String, Object> acctConfig = account.getConfig() != null
                    ? new java.util.HashMap<>(account.getConfig()) : new java.util.HashMap<>();
            acctConfig.put("oauth_credentials", credentialsJson);
            acctConfig.put("token_saved_at", OffsetDateTime.now().toString());
            account.setConfig(acctConfig);
            accountRepository.save(account);

            log.info("[{}] credentials 추출 및 DB 저장 완료", accountId);
            return Map.of("status", "ok", "message", "토큰 저장 완료",
                    "saved_at", acctConfig.get("token_saved_at"));

        } catch (Exception e) {
            log.error("[{}] 토큰 추출 실패: {}", accountId, e.getMessage());
            throw new RuntimeException("토큰 추출 실패: " + e.getMessage(), e);
        }
    }

    /**
     * DB에 저장된 토큰을 계정별 config 디렉토리에 배포.
     * 컨테이너 재빌드/재기동 후 호출하면 인증이 복원된다.
     */
    public Map<String, Object> deployToken(String accountId) {
        AgentAccountEntity account = accountRepository.findById(accountId).orElse(null);
        if (account == null) throw new IllegalArgumentException("계정 없음: " + accountId);

        if (!deployTokenToConfigDir(account)) {
            throw new IllegalStateException("배포할 토큰 없음. save-token API로 토큰을 등록하세요.");
        }

        log.info("[{}] 토큰 배포 완료", accountId);
        return Map.of("status", "ok", "message", "토큰 배포 완료");
    }

    /**
     * 계정의 DB 토큰을 config 디렉토리에 파일로 기록.
     * @return 배포 성공 여부
     */
    private boolean deployTokenToConfigDir(AgentAccountEntity account) {
        Map<String, Object> acctConfig = account.getConfig();
        if (acctConfig == null) return false;

        String accountId = account.getId();
        Path configDir = Path.of(getConfigDir(accountId));

        try {
            Files.createDirectories(configDir);

            // 1. extractAndSaveToken으로 저장된 credentials 파일 배포
            Object credentials = acctConfig.get("oauth_credentials");
            if (credentials != null) {
                String credJson = credentials instanceof String s ? s
                        : objectMapper.writeValueAsString(credentials);
                Files.writeString(configDir.resolve("credentials.json"),
                        credJson, StandardCharsets.UTF_8);
                log.debug("[{}] credentials.json 배포 완료 (extracted)", accountId);
                return true;
            }

            // 2. save-token API로 저장된 auth_token 배포
            String authType = (String) acctConfig.get("auth_type");
            String authToken = (String) acctConfig.get("auth_token");
            if (authToken != null && !authToken.isBlank()) {
                if ("api_key".equals(authType)) {
                    // API Key 방식은 파일 배포 불필요 (환경변수로 주입)
                    log.debug("[{}] API Key 방식 — 파일 배포 불필요", accountId);
                    return true;
                }
                // OAuth setup-token: .claude.json에 인증 정보 기록
                Map<String, Object> claudeJson = new java.util.LinkedHashMap<>();
                claudeJson.put("oauthToken", authToken);
                Files.writeString(configDir.resolve(".claude.json"),
                        objectMapper.writeValueAsString(claudeJson), StandardCharsets.UTF_8);
                log.debug("[{}] .claude.json 배포 완료 (setup-token)", accountId);
                return true;
            }

            return false;
        } catch (IOException e) {
            log.error("[{}] config 디렉토리 배포 실패: {}", accountId, e.getMessage());
            return false;
        }
    }

    private String readStream(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            log.error("스트림 읽기 실패: {}", e.getMessage());
            return "";
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
