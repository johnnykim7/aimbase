package com.platform.tool.builtin;

import com.platform.tool.model.UnifiedToolDef;
import com.platform.tool.*;
import com.platform.tool.registry.SessionToolRegistry;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CR-035 PRD-236: 도구 검색.
 * CR-048 PRD-300: 검색 결과를 SessionToolRegistry에 활성화하여 다음 턴부터 스키마 주입.
 */
@Component
public class ToolSearchTool implements EnhancedToolExecutor {

    private final ToolRegistry toolRegistry;
    private final SessionToolRegistry sessionToolRegistry;

    public ToolSearchTool(ToolRegistry toolRegistry, SessionToolRegistry sessionToolRegistry) {
        this.toolRegistry = toolRegistry;
        this.sessionToolRegistry = sessionToolRegistry;
    }

    @Override
    public UnifiedToolDef getDefinition() {
        return new UnifiedToolDef(
                "tool_search",
                "등록된 도구를 키워드, 태그, 스코프로 검색합니다. " +
                        "검색 결과에 포함된 도구는 현재 세션에 활성화되어 다음 턴부터 스키마가 주입됩니다.",
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

        List<Map<String, Object>> matched = toolRegistry.searchTools(query, tags, scope, maxResults);

        // CR-048 PRD-300: 검색 결과에 포함된 도구를 세션에 활성화
        List<String> activated = List.of();
        if (ctx != null && ctx.sessionId() != null && !matched.isEmpty()) {
            List<String> names = matched.stream()
                    .map(m -> (String) m.get("name"))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            activated = sessionToolRegistry.activateAll(ctx.sessionId(), names);
        }

        Map<String, Object> output = new HashMap<>();
        output.put("matched", matched);
        output.put("activated", activated);
        if (!activated.isEmpty()) {
            output.put("note", "이 도구들은 다음 턴부터 스키마가 주입됩니다.");
        }

        String summary = matched.size() + "개 도구 검색됨"
                + (query.isEmpty() ? "" : " (키워드: '" + query + "')")
                + (activated.isEmpty() ? "" : ", " + activated.size() + "개 신규 활성화");
        return ToolResult.ok(output, summary);
    }
}
