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
    private final UnifiedToolDef toolDef;

    public RemoteAgentToolExecutor(String agentMcpUrl, UnifiedToolDef toolDef) {
        this.agentMcpUrl = agentMcpUrl;
        this.toolDef = toolDef;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return toolDef;
    }

    @Override
    public String execute(Map<String, Object> input) {
        try (MCPServerClient client = new MCPServerClient(
                "remote-" + toolDef.name(), "http", Map.of("url", agentMcpUrl + "/mcp/sse"))) {
            client.connect();
            String result = client.callTool(toolDef.name(), input);
            log.debug("Remote tool '{}' executed via agent at {}", toolDef.name(), agentMcpUrl);
            return result;
        } catch (Exception e) {
            log.error("Remote tool '{}' execution failed (agent: {}): {}",
                    toolDef.name(), agentMcpUrl, e.getMessage());
            return "{\"error\":\"remote_execution_failed\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    /** 이 도구가 연결된 에이전트 MCP URL */
    public String getAgentMcpUrl() {
        return agentMcpUrl;
    }
}
