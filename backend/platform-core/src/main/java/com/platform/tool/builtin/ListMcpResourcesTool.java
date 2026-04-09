package com.platform.tool.builtin;

import com.platform.tool.model.UnifiedToolDef;
import com.platform.mcp.MCPServerClient;
import com.platform.mcp.MCPServerManager;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CR-038 PRD-245: MCP 리소스 목록 탐색.
 * 연결된 MCP 서버의 리소스를 에이전트가 자율적으로 탐색한다.
 */
@Component
public class ListMcpResourcesTool implements EnhancedToolExecutor {

    private final MCPServerManager mcpServerManager;

    public ListMcpResourcesTool(MCPServerManager mcpServerManager) {
        this.mcpServerManager = mcpServerManager;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "list_mcp_resources",
                "연결된 MCP 서버의 리소스(resource) 목록을 탐색합니다. " +
                        "MCP 서버가 제공하는 데이터, 파일, 설정 등을 확인할 때 사용합니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "server_id", Map.of("type", "string",
                                        "description", "특정 MCP 서버 ID로 필터 (미지정 시 전체 연결 서버)")
                        ),
                        "required", List.of()
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("list_mcp_resources",
                List.of("mcp", "resource", "discovery"));
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String serverId = (String) input.get("server_id");

        List<String> serverIds;
        if (serverId != null && !serverId.isBlank()) {
            MCPServerClient client = mcpServerManager.getClient(serverId);
            if (client == null) {
                return ToolResult.error("MCP 서버 '" + serverId + "'에 연결되어 있지 않습니다");
            }
            serverIds = List.of(serverId);
        } else {
            serverIds = mcpServerManager.getConnectedServerIds();
        }

        if (serverIds.isEmpty()) {
            return ToolResult.ok(Map.of("resources", List.of()),
                    "연결된 MCP 서버가 없습니다");
        }

        List<Map<String, Object>> allResources = new ArrayList<>();
        for (String sid : serverIds) {
            MCPServerClient client = mcpServerManager.getClient(sid);
            if (client != null) {
                allResources.addAll(client.listResources());
            }
        }

        return ToolResult.ok(
                Map.of("resources", allResources),
                allResources.size() + "개 리소스 발견 (" + serverIds.size() + "개 서버)"
        );
    }
}
