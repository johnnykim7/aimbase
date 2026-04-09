package com.platform.mcp;

import com.platform.domain.AgentRegistryEntity;
import com.platform.service.AgentRegistryService;
import com.platform.tool.ToolExecutor;
import com.platform.tool.ToolRegistry;
import com.platform.tool.model.UnifiedToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CR-041: BIZ-080 — 30초 주기로 활성 에이전트의 도구를 ToolRegistry에 동기화.
 * 원격 도구는 RemoteAgentToolExecutor로 등록되어 로컬 도구처럼 LLM에 노출된다.
 * 사라진 에이전트의 도구는 자동 해제된다.
 */
@Component
public class RemoteToolDiscovery {

    private static final Logger log = LoggerFactory.getLogger(RemoteToolDiscovery.class);

    private final AgentRegistryService agentRegistryService;
    private final ToolRegistry toolRegistry;

    /** 현재 등록된 원격 도구 이름 → 에이전트 MCP URL */
    private final Map<String, String> registeredRemoteTools = new ConcurrentHashMap<>();

    public RemoteToolDiscovery(AgentRegistryService agentRegistryService, ToolRegistry toolRegistry) {
        this.agentRegistryService = agentRegistryService;
        this.toolRegistry = toolRegistry;
    }

    /**
     * BIZ-080: 30초 주기 동기화.
     */
    @Scheduled(fixedRate = 30_000, initialDelay = 10_000)
    public void syncRemoteTools() {
        try {
            List<AgentRegistryEntity> agents = agentRegistryService.listActive();

            Set<String> currentRemoteTools = new HashSet<>();

            for (AgentRegistryEntity agent : agents) {
                String mcpUrl = agent.getMcpUrl();
                for (Map<String, Object> toolInfo : agent.getToolsCache()) {
                    String toolName = (String) toolInfo.get("name");
                    if (toolName == null) continue;

                    currentRemoteTools.add(toolName);

                    // 로컬에 이미 있는 도구는 건너뜀 (로컬 우선)
                    if (toolRegistry.getRegisteredToolNames().contains(toolName)
                            && !registeredRemoteTools.containsKey(toolName)) {
                        continue;
                    }

                    // 이미 같은 에이전트에서 등록된 도구 → 건너뜀
                    if (mcpUrl.equals(registeredRemoteTools.get(toolName))) {
                        continue;
                    }

                    // 다른 에이전트 도구로 교체되는 경우 기존 해제
                    if (registeredRemoteTools.containsKey(toolName)) {
                        toolRegistry.unregister(toolName);
                    }

                    // 원격 도구 등록
                    String description = (String) toolInfo.getOrDefault("description", "");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> inputSchema = (Map<String, Object>) toolInfo.getOrDefault("inputSchema", Map.of());
                    UnifiedToolDef def = new UnifiedToolDef(toolName, description, inputSchema);

                    ToolExecutor executor = new RemoteAgentToolExecutor(mcpUrl, def);
                    toolRegistry.register(executor);
                    registeredRemoteTools.put(toolName, mcpUrl);

                    log.info("Remote tool registered: {} (agent: {})", toolName, agent.getAgentName());
                }
            }

            // 사라진 에이전트 도구 해제
            Set<String> toRemove = new HashSet<>(registeredRemoteTools.keySet());
            toRemove.removeAll(currentRemoteTools);
            for (String toolName : toRemove) {
                toolRegistry.unregister(toolName);
                registeredRemoteTools.remove(toolName);
                log.info("Remote tool unregistered (agent gone): {}", toolName);
            }
        } catch (Exception e) {
            log.warn("Remote tool sync failed: {}", e.getMessage());
        }
    }
}
