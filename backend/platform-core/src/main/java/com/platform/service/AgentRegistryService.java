package com.platform.service;

import com.platform.domain.AgentRegistryEntity;
import com.platform.repository.AgentRegistryRepository;
import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CR-041: 원격 에이전트 레지스트리 서비스.
 * 에이전트 등록 시 MCP 연결 → 도구 탐색 → 캐시 저장.
 * BIZ-079: 5분 무응답 에이전트는 STALE 처리.
 */
@Service
public class AgentRegistryService {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistryService.class);
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(5);

    private final AgentRegistryRepository repository;
    private final TenantDataSourceManager tenantDataSourceManager;
    /** CR-081: agent 등록 시 다운스트림 (CB reset 등) 에 알리기 위한 이벤트 퍼블리셔. */
    private final ApplicationEventPublisher eventPublisher;

    public AgentRegistryService(AgentRegistryRepository repository,
                                TenantDataSourceManager tenantDataSourceManager,
                                ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.tenantDataSourceManager = tenantDataSourceManager;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 에이전트 등록.
     * 같은 주소:포트로 이미 ACTIVE 에이전트가 있으면 재등록(갱신).
     * CR-075: userId 가 주어지면 같은 userId 의 다른 ACTIVE agent 를 자동 DEREGISTER (사용자당 활성 1개 정책).
     * MCP 연결하여 도구 목록을 탐색 후 캐시에 저장.
     */
    public AgentRegistryEntity register(String agentName, String userId, String publicAddress, int mcpPort,
                                         List<String> toolNames, Map<String, Object> metadata) {
        // 기존 등록 확인 → 재등록
        Optional<AgentRegistryEntity> existing = repository
                .findByPublicAddressAndMcpPortAndStatus(publicAddress, mcpPort, "ACTIVE");
        AgentRegistryEntity entity = existing.orElseGet(AgentRegistryEntity::new);

        // CR-075: 같은 userId 의 이전 ACTIVE agent 자동 DEREGISTER (덮어쓰기 정책)
        // 본인 entity (재등록 케이스) 는 제외.
        if (userId != null && !userId.isBlank()) {
            for (AgentRegistryEntity prior : repository.findByUserId(userId)) {
                if ("ACTIVE".equals(prior.getStatus())
                        && (entity.getId() == null || !prior.getId().equals(entity.getId()))) {
                    prior.setStatus("DEREGISTERED");
                    prior.setDeregisteredAt(OffsetDateTime.now());
                    repository.save(prior);
                    log.info("CR-075: prior ACTIVE agent superseded by new registration — userId={}, oldId={}",
                            userId, prior.getId());
                }
            }
        }

        entity.setAgentName(agentName);
        entity.setUserId(userId);
        entity.setPublicAddress(publicAddress);
        entity.setMcpPort(mcpPort);
        entity.setStatus("ACTIVE");
        entity.setLastHeartbeatAt(OffsetDateTime.now());
        if (metadata != null) {
            entity.setMetadata(metadata);
            // TURN 릴레이 주소 추출
            Object turnRelay = metadata.get("turnRelayAddress");
            if (turnRelay instanceof String addr && !addr.isBlank()) {
                entity.setTurnRelayAddress(addr);
            }
            // CR-071 Phase 3: Runner 정보 추출
            Object runnerEndpoint = metadata.get("runnerEndpoint");
            if (runnerEndpoint instanceof String url && !url.isBlank()) {
                entity.setRunnerEndpoint(url);
                entity.setRunnerCapability(true);
            }
            Object runnerApiKeyHash = metadata.get("runnerApiKeyHash");
            if (runnerApiKeyHash instanceof String hash && !hash.isBlank()) {
                entity.setRunnerApiKeyHash(hash);
            }
        }
        if (existing.isEmpty()) entity.setRegisteredAt(OffsetDateTime.now());

        // CR-103(B): register 응답 경로에서 동기 MCP discover 를 제거한다.
        // 과거에는 여기서 discoverToolsFromAgent(entity) 를 동기 호출했으나, agent MCP 포트
        // 접속이 실패하면 ~30초 블로킹 → agent 의 register HTTP timeout(15s) 초과 → agent 가
        // agentId 를 못 받고 null heartbeat 전송 → 5분 뒤 STALE (운영 실측, 2026-06-13).
        // 도구 스키마는 RemoteToolDiscovery 가 주기적 백그라운드로 채운다(register 와 독립).
        // 여기서는 agent 가 register body 로 보낸 toolNames 로 즉시 캐시를 채우고 빠르게 응답한다.
        if (toolNames != null && !toolNames.isEmpty()) {
            entity.setToolsCache(toolNames.stream()
                    .map(name -> Map.<String, Object>of("name", name))
                    .toList());
        }

        boolean wasReregister = existing.isPresent();
        entity = repository.save(entity);
        log.info("Agent registered: id={}, name={}, address={}:{}, tools={}, reregister={}",
                entity.getId(), agentName, publicAddress, mcpPort,
                entity.getToolsCache().size(), wasReregister);

        // CR-081: 등록 이벤트 발행 — FallbackChainExecutor 등 다운스트림이 CB reset 등 회복 동작.
        // 발행 실패는 등록 자체에 영향 없도록 try-catch.
        try {
            eventPublisher.publishEvent(new AgentRegisteredEvent(
                    entity.getId().toString(), userId,
                    TenantContext.getTenantId(), wasReregister));
        } catch (Exception e) {
            log.warn("AgentRegisteredEvent 발행 실패 (무시): {}", e.getMessage());
        }
        return entity;
    }

    /**
     * CR-071 Phase 3: ClaudeCliAdapter 가 X-Aimbase-Agent-Id 헤더로 라우팅 시 호출.
     * 활성 + runner_capability=true 인 에이전트의 endpoint 를 반환.
     *
     * @param agentId AgentRegistry.id (UUID 문자열)
     * @return AgentEndpoint (없거나 비활성/Runner 미지원이면 빈 Optional)
     */
    public Optional<AgentEndpoint> resolveActiveRunner(String agentId) {
        if (agentId == null || agentId.isBlank()) return Optional.empty();
        UUID id;
        try {
            id = UUID.fromString(agentId);
        } catch (IllegalArgumentException e) {
            log.warn("resolveActiveRunner: 잘못된 agentId 형식: {}", agentId);
            return Optional.empty();
        }
        return repository.findById(id)
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .filter(AgentRegistryEntity::isRunnerCapability)
                .filter(a -> a.getRunnerEndpoint() != null && !a.getRunnerEndpoint().isBlank())
                .map(a -> new AgentEndpoint(
                        a.getId().toString(),
                        a.getRunnerEndpoint(),
                        a.getRunnerApiKeyHash()));
    }

    /**
     * CR-075: user_ref 기반 자동 라우팅.
     * 위젯 토큰의 user_ref 클레임으로 활성 + runner_capability=true agent 의 endpoint 를 반환.
     * 사용자당 활성 1개 정책({@link #register}) 으로 결과는 0 또는 1 건.
     *
     * @param userRef 위젯 토큰의 user_ref (사용자 ID)
     * @return AgentEndpoint (없거나 비활성/Runner 미지원이면 빈 Optional)
     */
    public Optional<AgentEndpoint> resolveActiveByUserRef(String userRef) {
        if (userRef == null || userRef.isBlank()) return Optional.empty();
        return repository.findByUserId(userRef).stream()
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .filter(AgentRegistryEntity::isRunnerCapability)
                .filter(a -> a.getRunnerEndpoint() != null && !a.getRunnerEndpoint().isBlank())
                // 다중 활성이 들어와도 안전하게 — 가장 최근 heartbeat 우선 (정상 케이스 1건)
                .max((a, b) -> a.getLastHeartbeatAt().compareTo(b.getLastHeartbeatAt()))
                .map(a -> new AgentEndpoint(
                        a.getId().toString(),
                        a.getRunnerEndpoint(),
                        a.getRunnerApiKeyHash()));
    }

    /**
     * CR-103: 커넥터 config 의 {@code agent_name} 기반 워크플로우 CLI 라우팅.
     * 워크플로우 스텝(LLM_CALL/AGENT_CALL)은 X-Aimbase-Agent-Id 헤더도 위젯 user_ref 도 없으므로,
     * CLI 커넥터에 박힌 agent_name 으로 활성 + runner_capability=true agent 의 endpoint 를 결정한다.
     * 동명 다중 등록(재등록 누적)이 들어와도 가장 최근 heartbeat 우선으로 1건 선택.
     *
     * @param agentName 커넥터 config.agent_name (AgentRegistry.agent_name)
     * @return AgentEndpoint (없거나 비활성/Runner 미지원이면 빈 Optional)
     */
    public Optional<AgentEndpoint> resolveActiveByAgentName(String agentName) {
        if (agentName == null || agentName.isBlank()) return Optional.empty();
        return repository.findByAgentName(agentName).stream()
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .filter(AgentRegistryEntity::isRunnerCapability)
                .filter(a -> a.getRunnerEndpoint() != null && !a.getRunnerEndpoint().isBlank())
                .max((a, b) -> a.getLastHeartbeatAt().compareTo(b.getLastHeartbeatAt()))
                .map(a -> new AgentEndpoint(
                        a.getId().toString(),
                        a.getRunnerEndpoint(),
                        a.getRunnerApiKeyHash()));
    }

    /**
     * CR-071 Phase 3: Runner 능력 정보 갱신. 등록/하트비트 흐름과 별개로 호출 가능.
     */
    public void updateRunnerCapability(UUID agentId, String runnerEndpoint,
                                       String runnerApiKeyHash, boolean capability) {
        repository.findById(agentId).ifPresent(entity -> {
            entity.setRunnerEndpoint(runnerEndpoint);
            entity.setRunnerApiKeyHash(runnerApiKeyHash);
            entity.setRunnerCapability(capability);
            repository.save(entity);
            log.info("Agent runner capability updated: id={}, endpoint={}, capability={}",
                    agentId, runnerEndpoint, capability);
        });
    }

    /**
     * 에이전트 해제.
     */
    public void deregister(UUID agentId) {
        repository.findById(agentId).ifPresent(entity -> {
            entity.setStatus("DEREGISTERED");
            entity.setDeregisteredAt(OffsetDateTime.now());
            repository.save(entity);
            log.info("Agent deregistered: id={}, name={}", agentId, entity.getAgentName());
        });
    }

    /**
     * 하트비트 갱신.
     */
    public void heartbeat(UUID agentId) {
        repository.findById(agentId).ifPresent(entity -> {
            entity.setLastHeartbeatAt(OffsetDateTime.now());
            if ("STALE".equals(entity.getStatus())) {
                entity.setStatus("ACTIVE");
                log.info("Agent recovered from STALE: id={}", agentId);
            }
            repository.save(entity);
        });
    }

    /**
     * 활성 에이전트 목록 조회.
     */
    public List<AgentRegistryEntity> listActive() {
        return repository.findByStatus("ACTIVE");
    }

    /**
     * 상태별 에이전트 목록 조회.
     */
    public List<AgentRegistryEntity> listByStatus(String status) {
        return repository.findByStatus(status);
    }

    /**
     * 특정 도구를 가진 에이전트 찾기.
     */
    public Optional<AgentRegistryEntity> findAgentWithTool(String toolName) {
        return listActive().stream()
                .filter(agent -> agent.getToolsCache().stream()
                        .anyMatch(tool -> toolName.equals(tool.get("name"))))
                .findFirst();
    }

    /**
     * BIZ-079: 5분 무응답 에이전트 STALE 처리.
     */
    @Scheduled(fixedRate = 60_000)
    public void cleanupStaleAgents() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(STALE_THRESHOLD);
        for (String tenantId : tenantDataSourceManager.getAllCachedDataSources().keySet()) {
            try {
                TenantContext.setTenantId(tenantId);
                List<AgentRegistryEntity> stale = repository
                        .findByStatusAndLastHeartbeatAtBefore("ACTIVE", cutoff);
                for (AgentRegistryEntity agent : stale) {
                    agent.setStatus("STALE");
                    repository.save(agent);
                    log.warn("Agent marked STALE: tenant={}, id={}, name={}, lastHeartbeat={}",
                            tenantId, agent.getId(), agent.getAgentName(), agent.getLastHeartbeatAt());
                }
            } catch (Exception e) {
                log.warn("cleanupStaleAgents failed for tenant {}: {}", tenantId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    // CR-103(B): discoverToolsFromAgent(...) 동기 도구 탐색 메서드 제거.
    // register 응답 블로킹의 원인이었고(MCP 접속 실패 시 ~30초), 도구 등록은
    // RemoteToolDiscovery 의 주기적 백그라운드 경로가 담당한다(중복 제거).
    // 도구 스키마 보강이 필요하면 RemoteToolDiscovery 경로에서 다룬다.
}
