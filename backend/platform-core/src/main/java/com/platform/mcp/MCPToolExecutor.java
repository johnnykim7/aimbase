package com.platform.mcp;

import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * MCPServerClient의 특정 도구를 ToolExecutor 인터페이스로 래핑.
 * ToolRegistry에 등록되어 ToolCallHandler에서 실행 가능.
 *
 * lazy reconnect:
 * - 도구 호출이 끊긴 연결 등으로 실패하면 manager 를 통해 1회 재연결 후 재시도.
 * - manager 가 null 이면(테스트 경로) 재시도 없이 원본 예외 전파.
 */
public class MCPToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(MCPToolExecutor.class);

    private final MCPServerClient client;
    private final UnifiedToolDef definition;
    private final MCPServerManager manager;
    private final String serverId;

    public MCPToolExecutor(MCPServerClient client, UnifiedToolDef definition) {
        this(client, definition, null, null);
    }

    public MCPToolExecutor(MCPServerClient client, UnifiedToolDef definition,
                            MCPServerManager manager, String serverId) {
        this.client = client;
        this.definition = definition;
        this.manager = manager;
        this.serverId = serverId;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return definition;
    }

    @Override
    public String execute(Map<String, Object> input) {
        try {
            return client.callTool(definition.name(), input);
        } catch (MCPToolErrorException toolErr) {
            // CR-127: 도구 로직 에러(잘못된 인자 등)는 전송 장애가 아니다.
            // 재연결·재시도해도 같은 결과이므로 그대로 올려보내 호출자가 실패로 처리하게 한다.
            throw toolErr;
        } catch (Exception e) {
            if (manager == null || serverId == null) {
                throw e;
            }
            log.warn("MCP tool '{}' call failed on server '{}': {} — attempting lazy reconnect",
                    definition.name(), serverId, e.getMessage());
            try {
                manager.reconnect(serverId);
            } catch (Exception reconnectErr) {
                log.warn("Lazy reconnect for server '{}' failed: {}", serverId, reconnectErr.getMessage());
                throw e;
            }
            MCPServerClient fresh = manager.getClient(serverId);
            if (fresh == null) {
                throw e;
            }
            return fresh.callTool(definition.name(), input);
        }
    }
}
