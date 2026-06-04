package com.platform.mcp.agent;

import com.platform.tool.McpResultTruncator;
import com.platform.tool.ToolExecutor;
import com.platform.tool.model.UnifiedToolDef;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/**
 * CR-072: ToolExecutor → MCP 변환 유틸. {@link AgentMcpServer} 와 서버 측
 * MCP 어댑터(platform-core)가 공유하는 공통 코드.
 *
 * <p>변환 규칙은 {@link AgentMcpServer#dispatch}, {@code toJsonSchemaStatic} 와 동일하다.
 * SDK 도구(agent stdio/SSE)와 서버 도구(platform-core /mcp/sse) 모두 같은 변환을 거쳐
 * Claude CLI 등 MCP 클라이언트에 일관된 형태로 노출된다.</p>
 */
public final class McpToolConversion {

    private McpToolConversion() {
    }

    /** UnifiedToolDef 의 inputSchema(JSONSchema Map) 를 MCP JsonSchema 로 변환. */
    @SuppressWarnings("unchecked")
    public static McpSchema.JsonSchema toJsonSchema(Map<String, Object> schema) {
        if (schema == null) {
            return new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null);
        }
        String type = (String) schema.getOrDefault("type", "object");
        Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());
        Boolean additionalProperties = (Boolean) schema.get("additionalProperties");
        return new McpSchema.JsonSchema(type, properties, required, additionalProperties, null, null);
    }

    /** ToolExecutor → MCP Tool 메타로 변환 (이름/설명/입력스키마). */
    public static McpSchema.Tool toMcpTool(ToolExecutor tool) {
        UnifiedToolDef def = tool.getDefinition();
        // SDK 0.17.0: Tool 7-arg 생성자 대신 builder 사용.
        return McpSchema.Tool.builder()
                .name(def.name())
                .description(def.description())
                .inputSchema(toJsonSchema(def.inputSchema()))
                .build();
    }

    /**
     * 도구 호출 디스패치 — 결과를 MCP {@link McpSchema.CallToolResult} 로 패킹.
     *
     * <p>CR-067: EnhancedToolExecutor 의 default bridge 가 {@code ToolResultRenderer} 로
     * 본문을 직렬화하므로 {@code tool.execute(args)} 한 줄로 본문 노출 가능.
     * {@link McpResultTruncator} 로 길이 제한 적용. 예외는 isError=true 로 패킹.</p>
     */
    public static McpSchema.CallToolResult dispatch(ToolExecutor tool, String name, Map<String, Object> args) {
        try {
            String result = tool.execute(args);
            result = McpResultTruncator.truncate(name, result);
            return new McpSchema.CallToolResult(result, false);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new McpSchema.CallToolResult(
                    "{\"error\":\"" + msg.replace("\"", "\\\"") + "\"}", true);
        }
    }
}
