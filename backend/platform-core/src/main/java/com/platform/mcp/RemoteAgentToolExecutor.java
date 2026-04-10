package com.platform.mcp;

import com.platform.tool.ToolExecutor;
import com.platform.tool.model.UnifiedToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * CR-041: 원격 에이전트의 MCP 도구를 ToolExecutor로 래핑.
 * ToolRegistry에 등록되어 로컬 도구처럼 사용된다.
 * 실행 시 온디맨드로 MCP 연결 → 도구 호출 → 연결 종료.
 */
public class RemoteAgentToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(RemoteAgentToolExecutor.class);

    private final String agentMcpUrl;
    private final String turnMcpUrl;
    private final UnifiedToolDef toolDef;

    public RemoteAgentToolExecutor(String agentMcpUrl, UnifiedToolDef toolDef) {
        this(agentMcpUrl, null, toolDef);
    }

    public RemoteAgentToolExecutor(String agentMcpUrl, String turnMcpUrl, UnifiedToolDef toolDef) {
        this.agentMcpUrl = agentMcpUrl;
        this.turnMcpUrl = turnMcpUrl;
        this.toolDef = toolDef;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return toolDef;
    }

    @Override
    public String execute(Map<String, Object> input) {
        // 1차: 직접 연결 시도
        try (MCPServerClient client = new MCPServerClient(
                "remote-" + toolDef.name(), "http", Map.of("url", agentMcpUrl + "/mcp/sse"))) {
            client.connect();
            String result = client.callTool(toolDef.name(), input);
            log.debug("Remote tool '{}' executed via agent at {}", toolDef.name(), agentMcpUrl);
            return result;
        } catch (Exception directEx) {
            log.warn("Direct connection failed for tool '{}' (agent: {}): {}",
                    toolDef.name(), agentMcpUrl, directEx.getMessage());

            // 2차: TURN 릴레이 폴백
            if (turnMcpUrl != null && !turnMcpUrl.isBlank()) {
                try (MCPServerClient turnClient = new MCPServerClient(
                        "turn-" + toolDef.name(), "http",
                        Map.of("url", turnMcpUrl + "/mcp/sse"))) {
                    turnClient.connect();
                    String result = turnClient.callTool(toolDef.name(), input);
                    log.info("Remote tool '{}' executed via TURN relay at {}", toolDef.name(), turnMcpUrl);
                    return result;
                } catch (Exception turnEx) {
                    log.error("TURN fallback also failed for tool '{}' (relay: {}): {}",
                            toolDef.name(), turnMcpUrl, turnEx.getMessage());
                }
            }

            return "{\"error\":\"remote_execution_failed\",\"message\":\"" + directEx.getMessage() + "\"}";
        }
    }

    /** 이 도구가 연결된 에이전트 MCP URL */
    public String getAgentMcpUrl() {
        return agentMcpUrl;
    }
}
