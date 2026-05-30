package com.platform.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.MCPServerEntity;
import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.repository.MCPServerRepository;
import com.platform.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 서버 연결 풀을 관리하는 컴포넌트.
 *
 * - 앱 시작 시 autoStart=true 서버에 자동 연결 (실패 시 warn, non-fatal)
 * - discover(serverId) 호출 시 도구 탐색 → DB tools_cache 업데이트 → ToolRegistry 등록
 * - disconnect(serverId) 호출 시 연결 해제 + ToolRegistry에서 도구 제거
 */
@Component
public class MCPServerManager {

    private static final Logger log = LoggerFactory.getLogger(MCPServerManager.class);

    private final MCPServerRepository mcpServerRepository;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final TenantDataSourceManager tenantDataSourceManager;
    private final Map<String, MCPServerClient> connections = new ConcurrentHashMap<>();

    public MCPServerManager(MCPServerRepository mcpServerRepository,
                             @Lazy ToolRegistry toolRegistry,
                             ObjectMapper objectMapper,
                             TenantDataSourceManager tenantDataSourceManager) {
        this.mcpServerRepository = mcpServerRepository;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.tenantDataSourceManager = tenantDataSourceManager;
    }

    /**
     * 앱 시작 시 autoStart=true인 MCP 서버에 자동 연결.
     * 연결 실패는 warn 로그만 출력 (개발 환경에서 서버가 없을 수 있으므로 non-fatal).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void connectAutoStartServers() {
        for (String tenantId : tenantDataSourceManager.getAllCachedDataSources().keySet()) {
            try {
                TenantContext.setTenantId(tenantId);
                List<MCPServerEntity> autoStartServers = mcpServerRepository.findAll()
                        .stream()
                        .filter(MCPServerEntity::isAutoStart)
                        .toList();

                if (autoStartServers.isEmpty()) {
                    continue;
                }

                log.info("Auto-connecting {} MCP server(s) for tenant {}...", autoStartServers.size(), tenantId);
                for (MCPServerEntity server : autoStartServers) {
                    try {
                        connect(server);
                        log.info("Auto-connected to MCP server: {} (tenant {})", server.getId(), tenantId);
                    } catch (Exception e) {
                        log.warn("Failed to auto-connect to MCP server '{}' (tenant {}): {}",
                                server.getId(), tenantId, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Could not load MCP servers for tenant {}: {}", tenantId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    /**
     * MCP 서버 도구 탐색.
     * 연결 → 도구 목록 조회 → DB tools_cache 업데이트 → ToolRegistry 등록.
     *
     * @param serverId MCP 서버 ID
     * @return 탐색된 도구 목록
     */
    public List<UnifiedToolDef> discover(String serverId) {
        MCPServerEntity entity = mcpServerRepository.findById(serverId)
                .orElseThrow(() -> new IllegalArgumentException("MCP server not found: " + serverId));

        // 연결 (이미 연결된 경우 재사용)
        MCPServerClient client = connections.computeIfAbsent(serverId,
                id -> createClient(entity));
        if (!client.isInitialized()) {
            client.connect();
        }

        // 도구 탐색
        List<UnifiedToolDef> tools = client.discoverTools();

        // DB tools_cache 업데이트 + 상태 변경
        try {
            entity.setToolsCache(tools);
            entity.setStatus("connected");
            mcpServerRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to update tools_cache for server '{}': {}", serverId, e.getMessage());
        }

        // ToolRegistry에 MCP 도구 등록 (기존 동일 이름 도구 덮어쓰기)
        // manager + serverId 를 넘겨 lazy reconnect 가 가능하게 함.
        for (UnifiedToolDef tool : tools) {
            toolRegistry.register(new MCPToolExecutor(client, tool, this, serverId));
        }
        log.info("Registered {} tool(s) from MCP server '{}'", tools.size(), serverId);

        return tools;
    }

    /**
     * MCP 서버 재연결.
     * 기존 연결을 닫고(있다면), discover 를 통해 새 client 로 재연결 + 도구 재등록.
     * 호출자는 TenantContext 를 미리 설정해야 한다.
     *
     * @param serverId MCP 서버 ID
     * @return 재연결 후 등록된 도구 목록
     */
    public List<UnifiedToolDef> reconnect(String serverId) {
        MCPServerClient existing = connections.remove(serverId);
        if (existing != null) {
            try {
                existing.close();
            } catch (Exception e) {
                log.warn("Error closing stale MCP client for server '{}': {}", serverId, e.getMessage());
            }
        }
        // 상태를 disconnected 로 일단 기록 (discover 가 성공하면 connected 로 갱신)
        mcpServerRepository.findById(serverId).ifPresent(entity -> {
            entity.setStatus("disconnected");
            mcpServerRepository.save(entity);
        });
        return discover(serverId);
    }

    /**
     * 주기 health check + 자동 재연결.
     *
     * 60초마다 모든 캐시된 테넌트의 autoStart=true 서버를 점검:
     * - connections 에 없는 서버 → 재연결 시도 (실패는 warn 만)
     * - connections 에 있지만 client.isInitialized()==false → 재연결 시도
     *
     * ApplicationReadyEvent autoconnect 의 일시적 실패(SSE cold start, 일시 네트워크 장애 등)
     * 를 자동 복구하기 위한 안전망. initialDelay=120s 로 부팅 직후 첫 autoconnect 와 겹치지 않게 함.
     */
    @Scheduled(fixedDelayString = "${aimbase.mcp.healthcheck.interval-ms:60000}",
               initialDelayString = "${aimbase.mcp.healthcheck.initial-delay-ms:120000}")
    public void healthCheckAndReconnect() {
        for (String tenantId : tenantDataSourceManager.getAllCachedDataSources().keySet()) {
            try {
                TenantContext.setTenantId(tenantId);
                List<MCPServerEntity> autoStartServers = mcpServerRepository.findAll()
                        .stream()
                        .filter(MCPServerEntity::isAutoStart)
                        .toList();
                for (MCPServerEntity server : autoStartServers) {
                    MCPServerClient current = connections.get(server.getId());
                    boolean needsReconnect = current == null || !current.isInitialized();
                    if (!needsReconnect) {
                        continue;
                    }
                    try {
                        reconnect(server.getId());
                        log.info("Health check reconnected MCP server '{}' (tenant {})",
                                server.getId(), tenantId);
                    } catch (Exception e) {
                        log.warn("Health check reconnect failed for MCP server '{}' (tenant {}): {}",
                                server.getId(), tenantId, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Health check failed for tenant {}: {}", tenantId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    /**
     * MCP 서버 연결 해제.
     * 연결을 닫고 해당 서버의 도구를 ToolRegistry에서 제거.
     *
     * @param serverId MCP 서버 ID
     */
    public void disconnect(String serverId) {
        MCPServerClient client = connections.remove(serverId);
        if (client != null) {
            // 해당 서버에서 등록된 도구 제거
            try {
                List<UnifiedToolDef> serverTools = client.discoverTools();
                serverTools.forEach(t -> toolRegistry.unregister(t.name()));
            } catch (Exception e) {
                log.warn("Could not retrieve tool list for cleanup from server '{}': {}", serverId, e.getMessage());
            }
            client.close();

            // DB 상태 업데이트
            mcpServerRepository.findById(serverId).ifPresent(entity -> {
                entity.setStatus("disconnected");
                mcpServerRepository.save(entity);
            });
        }
    }

    /** 연결 중인 서버 ID 목록 */
    public List<String> getConnectedServerIds() {
        return List.copyOf(connections.keySet());
    }

    /** 특정 서버의 클라이언트 반환 (연결 안 되어 있으면 null) */
    public MCPServerClient getClient(String serverId) {
        return connections.get(serverId);
    }

    private MCPServerClient connect(MCPServerEntity entity) {
        MCPServerClient client = createClient(entity);
        client.connect();
        connections.put(entity.getId(), client);
        return client;
    }

    private MCPServerClient createClient(MCPServerEntity entity) {
        return new MCPServerClient(entity.getId(), entity.getTransport(), entity.getConfig());
    }
}
