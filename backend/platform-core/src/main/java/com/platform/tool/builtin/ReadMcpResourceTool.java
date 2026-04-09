package com.platform.tool.builtin;

import com.platform.tool.model.UnifiedToolDef;
import com.platform.mcp.MCPServerClient;
import com.platform.mcp.MCPServerManager;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-038 PRD-246: MCP 리소스 읽기.
 * 에이전트가 MCP 리소스 URI로 콘텐츠를 직접 읽는다.
 * BIZ-066: 텍스트 32KB 트렁케이션.
 */
@Component
public class ReadMcpResourceTool implements EnhancedToolExecutor {

    private final MCPServerManager mcpServerManager;
    private final com.platform.config.PlatformSettingsService platformSettings;

    public ReadMcpResourceTool(MCPServerManager mcpServerManager,
                               com.platform.config.PlatformSettingsService platformSettings) {
        this.mcpServerManager = mcpServerManager;
        this.platformSettings = platformSettings;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "read_mcp_resource",
                "MCP 리소스 URI를 지정하여 콘텐츠를 읽습니다. " +
                        "list_mcp_resources로 탐색한 리소스의 내용을 확인할 때 사용합니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "uri", Map.of("type", "string",
                                        "description", "읽을 리소스 URI"),
                                "server_id", Map.of("type", "string",
                                        "description", "MCP 서버 ID (미지정 시 전체 서버에서 탐색)")
                        ),
                        "required", List.of("uri")
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("read_mcp_resource",
                List.of("mcp", "resource", "read"));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolContext ctx) {
        String uri = (String) input.get("uri");
        if (uri == null || uri.isBlank()) {
            return ValidationResult.fail("uri는 필수입니다");
        }
        return ValidationResult.OK;
    }

    @Override
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String uri = (String) input.get("uri");
        String serverId = (String) input.get("server_id");

        MCPServerClient client = resolveClient(serverId);
        if (client == null) {
            return ToolResult.error(serverId != null
                    ? "MCP 서버 '" + serverId + "'에 연결되어 있지 않습니다"
                    : "연결된 MCP 서버가 없습니다");
        }

        try {
            Map<String, Object> result = client.readResource(uri);

            // BIZ-066 + CR-040: 텍스트 트렁케이션 (런타임 설정)
            int maxTextLength = platformSettings.getInt("session.message-body-max-bytes", 32768);
            boolean truncated = false;
            if ("text".equals(result.get("type"))) {
                String content = (String) result.get("content");
                if (content != null && content.length() > maxTextLength) {
                    result.put("content", content.substring(0, maxTextLength));
                    truncated = true;
                }
            }
            result.put("truncated", truncated);

            String mimeType = (String) result.getOrDefault("mimeType", "unknown");
            String summary = "리소스 읽기 완료: " + uri + " (" + mimeType + ")";
            if (truncated) {
                summary += " [32KB 초과 — 트렁케이션됨]";
            }

            return ToolResult.ok(result, summary);
        } catch (Exception e) {
            return ToolResult.error("리소스 읽기 실패: " + e.getMessage());
        }
    }

    private MCPServerClient resolveClient(String serverId) {
        if (serverId != null && !serverId.isBlank()) {
            return mcpServerManager.getClient(serverId);
        }
        // serverId 미지정 시 첫 번째 연결된 서버 사용
        List<String> ids = mcpServerManager.getConnectedServerIds();
        if (ids.isEmpty()) return null;
        return mcpServerManager.getClient(ids.get(0));
    }
}
