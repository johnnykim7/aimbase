package com.platform.tool.builtin;

import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * CR-035 PRD-236: 도구 검색.
 * ToolContractMeta의 tags/capabilities/description 기반 키워드 검색.
 */
@Component
public class ToolSearchTool implements EnhancedToolExecutor {

    private final ToolRegistry toolRegistry;

    public ToolSearchTool(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "tool_search",
                "등록된 도구를 키워드, 태그, 스코프로 검색합니다. " +
                        "어떤 도구가 있는지 모를 때 사용합니다.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string",
                                        "description", "검색 키워드 (도구 이름/설명에서 검색)"),
                                "tags", Map.of("type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "태그 필터 (예: ['file', 'read'])"),
                                "scope", Map.of("type", "string",
                                        "enum", List.of("BUILTIN", "MCP", "NATIVE", "EXTERNAL"),
                                        "description", "도구 스코프 필터 (선택)"),
                                "max_results", Map.of("type", "integer",
                                        "description", "최대 결과 수 (기본: 20)")
                        ),
                        "required", List.of()
                )
        );
    }

    @Override
    public ToolContractMeta getContractMeta() {
        return ToolContractMeta.readOnlyNative("tool_search",
                List.of("discovery", "search", "tool-management"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
        String query = (String) input.getOrDefault("query", "");
        List<String> tags = (List<String>) input.get("tags");
        String scope = (String) input.get("scope");
        int maxResults = input.containsKey("max_results")
                ? ((Number) input.get("max_results")).intValue() : 20;

        List<Map<String, Object>> results = toolRegistry.searchTools(query, tags, scope, maxResults);

        return ToolResult.ok(
                results,
                results.size() + "개 도구 검색됨" + (query.isEmpty() ? "" : " (키워드: '" + query + "')")
        );
    }
}
