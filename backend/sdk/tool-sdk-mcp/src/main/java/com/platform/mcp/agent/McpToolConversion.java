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

    /**
     * UnifiedToolDef 의 inputSchema(JSONSchema Map) 를 MCP inputSchema Map 으로 정규화.
     *
     * <p>CR-124 (SDK 2.0.0): {@code McpSchema.JsonSchema} 레코드 대신 {@code Map<String,Object>} 로
     * 다룬다. 2.0.0 의 {@code Tool.inputSchema()} 게터 반환 타입이 Map 으로 바뀌었기 때문에
     * 생성·소비 양쪽을 Map 기준으로 통일한다.</p>
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toJsonSchema(Map<String, Object> schema) {
        if (schema == null) {
            return Map.of("type", "object", "properties", Map.of(), "required", List.of());
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("type", schema.getOrDefault("type", "object"));
        out.put("properties", schema.getOrDefault("properties", Map.of()));
        out.put("required", schema.getOrDefault("required", List.of()));
        Object additionalProperties = schema.get("additionalProperties");
        if (additionalProperties != null) {
            out.put("additionalProperties", additionalProperties);
        }
        return out;
    }

    /** ToolExecutor → MCP Tool 메타로 변환 (이름/설명/입력스키마). */
    public static McpSchema.Tool toMcpTool(ToolExecutor tool) {
        UnifiedToolDef def = tool.getDefinition();
        // SDK 2.0.0: inputSchema 는 Map 오버로드 사용.
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
            // CR-124 (SDK 2.0.0): (String, boolean) 축약 생성자 제거 → builder 사용.
            return McpSchema.CallToolResult.builder()
                    .addTextContent(result)
                    .isError(false)
                    .build();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return McpSchema.CallToolResult.builder()
                    .addTextContent("{\"error\":\"" + msg.replace("\"", "\\\"") + "\"}")
                    .isError(true)
                    .build();
        }
    }
}
