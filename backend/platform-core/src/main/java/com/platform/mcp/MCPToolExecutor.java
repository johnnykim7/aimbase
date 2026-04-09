package com.platform.mcp;

import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.ToolExecutor;

import java.util.Map;

/**
 * MCPServerClient의 특정 도구를 ToolExecutor 인터페이스로 래핑.
 * ToolRegistry에 등록되어 ToolCallHandler에서 실행 가능.
 */
public class MCPToolExecutor implements ToolExecutor {

    private final MCPServerClient client;
    private final UnifiedToolDef definition;

    public MCPToolExecutor(MCPServerClient client, UnifiedToolDef definition) {
        this.client = client;
        this.definition = definition;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return definition;
    }

    @Override
    public String execute(Map<String, Object> input) {
        return client.callTool(definition.name(), input);
    }
}
