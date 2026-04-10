package com.platform.service;

import com.platform.domain.AgentRegistryEntity;
import com.platform.mcp.MCPServerClient;
import com.platform.repository.AgentRegistryRepository;
import com.platform.tool.model.UnifiedToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public AgentRegistryService(AgentRegistryRepository repository) {
        this.repository = repository;
    }

    /**
     * 에이전트 등록.
     * 같은 주소:포트로 이미 ACTIVE 에이전트가 있으면 재등록(갱신).
     * MCP 연결하여 도구 목록을 탐색 후 캐시에 저장.
     */
    public AgentRegistryEntity register(String agentName, String publicAddress, int mcpPort,
                                         List<String> toolNames, Map<String, Object> metadata) {
        // 기존 등록 확인 → 재등록
        Optional<AgentRegistryEntity> existing = repository
                .findByPublicAddressAndMcpPortAndStatus(publicAddress, mcpPort, "ACTIVE");
        AgentRegistryEntity entity = existing.orElseGet(AgentRegistryEntity::new);

        entity.setAgentName(agentName);
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
        }
        if (existing.isEmpty()) entity.setRegisteredAt(OffsetDateTime.now());

        // MCP 연결하여 실제 도구 탐색
        List<Map<String, Object>> discoveredTools = discoverToolsFromAgent(entity);
        if (!discoveredTools.isEmpty()) {
            entity.setToolsCache(discoveredTools);
        } else if (toolNames != null && !toolNames.isEmpty()) {
            // MCP 연결 실패 시 클라이언트가 보낸 도구명 목록 사용
            entity.setToolsCache(toolNames.stream()
                    .map(name -> Map.<String, Object>of("name", name))
                    .toList());
        }

        entity = repository.save(entity);
        log.info("Agent registered: id={}, name={}, address={}:{}, tools={}",
                entity.getId(), agentName, publicAddress, mcpPort,
                entity.getToolsCache().size());
        return entity;
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
        List<AgentRegistryEntity> stale = repository
                .findByStatusAndLastHeartbeatAtBefore("ACTIVE", cutoff);

        for (AgentRegistryEntity agent : stale) {
            agent.setStatus("STALE");
            repository.save(agent);
            log.warn("Agent marked STALE: id={}, name={}, lastHeartbeat={}",
                    agent.getId(), agent.getAgentName(), agent.getLastHeartbeatAt());
        }
    }

    /**
     * 에이전트의 MCP 서버에 연결하여 도구 목록 탐색.
     */
    private List<Map<String, Object>> discoverToolsFromAgent(AgentRegistryEntity agent) {
        String mcpUrl = agent.getMcpUrl() + "/mcp/sse";
        try (MCPServerClient client = new MCPServerClient(
                "agent-" + agent.getAgentName(), "http", Map.of("url", mcpUrl))) {
            client.connect();
            List<UnifiedToolDef> tools = client.discoverTools();
            return tools.stream()
                    .map(t -> Map.<String, Object>of(
                            "name", t.name(),
                            "description", t.description() != null ? t.description() : "",
                            "inputSchema", t.inputSchema() != null ? t.inputSchema() : Map.of()))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to discover tools from agent {}:{} — {}",
                    agent.getPublicAddress(), agent.getMcpPort(), e.getMessage());
            return List.of();
        }
    }
}
