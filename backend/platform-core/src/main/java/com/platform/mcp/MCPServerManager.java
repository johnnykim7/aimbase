package com.platform.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.MCPServerEntity;
import com.platform.llm.model.UnifiedToolDef;
import com.platform.repository.MCPServerRepository;
import com.platform.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
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
    private final Map<String, MCPServerClient> connections = new ConcurrentHashMap<>();

    public MCPServerManager(MCPServerRepository mcpServerRepository,
                             @Lazy ToolRegistry toolRegistry,
                             ObjectMapper objectMapper) {
        this.mcpServerRepository = mcpServerRepository;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 앱 시작 시 autoStart=true인 MCP 서버에 자동 연결.
     * 연결 실패는 warn 로그만 출력 (개발 환경에서 서버가 없을 수 있으므로 non-fatal).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void connectAutoStartServers() {
        // tenant context 없는 상태에서 실행되므로, DB 조회 실패(테이블 미존재 등)는 warn만 출력 (non-fatal)
        try {
            List<MCPServerEntity> autoStartServers = mcpServerRepository.findAll()
                    .stream()
                    .filter(MCPServerEntity::isAutoStart)
                    .toList();

            if (autoStartServers.isEmpty()) {
                log.info("No auto-start MCP servers configured");
                return;
            }

            log.info("Auto-connecting {} MCP server(s)...", autoStartServers.size());
            for (MCPServerEntity server : autoStartServers) {
                try {
                    connect(server);
                    log.info("Auto-connected to MCP server: {}", server.getId());
                } catch (Exception e) {
                    log.warn("Failed to auto-connect to MCP server '{}': {} (server may not be running)",
                            server.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Could not load MCP servers at startup (tenant DB may not be initialized yet): {}",
                    e.getMessage());
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
        for (UnifiedToolDef tool : tools) {
            toolRegistry.register(new MCPToolExecutor(client, tool));
        }
        log.info("Registered {} tool(s) from MCP server '{}'", tools.size(), serverId);

        return tools;
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
