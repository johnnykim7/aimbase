package com.platform.tool;

import com.platform.mcp.server.McpExposurePolicy;
import com.platform.monitoring.PlatformMetrics;
import com.platform.tool.model.UnifiedToolDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-110: ToolRegistry.getToolDefs(filter) 의 노출 정책 필터 검증.
 *
 * <p>핵심 불변식: API 경로(getToolDefs)도 노출 정책을 코드로 참조하여 CLI 노출 집합과 동일해진다.
 * 단 외부 MCP 도구(builtin 아님)는 정책 대상이 아니므로 항상 통과.</p>
 */
class ToolRegistryExposureFilterTest {

    private McpExposurePolicy policy;

    @BeforeEach
    void setUp() {
        policy = mock(McpExposurePolicy.class);
        // web_search/bash → CLI, 그 외(parse_document, team_create) → NONE
        when(policy.resolve(any(ToolExecutor.class))).thenAnswer(inv -> {
            ToolExecutor t = inv.getArgument(0);
            String name = t.getDefinition().name();
            return ("web_search".equals(name) || "bash".equals(name))
                    ? McpExposureLevel.CLI : McpExposureLevel.NONE;
        });
    }

    @Test
    void builtin_policy_NONE_tool_is_excluded_from_api_tools() {
        // builtin 으로 등록된 parse_document 는 정책 NONE → API 도구목록에서 제외.
        ToolRegistry r = builtinRegistry(List.of(builtinTool("web_search"), builtinTool("parse_document")));

        List<String> names = r.getToolDefs(ToolFilterContext.none()).stream()
                .map(UnifiedToolDef::name).toList();
        assertThat(names).contains("web_search");
        assertThat(names).doesNotContain("parse_document");
    }

    @Test
    void external_mcp_tool_always_passes_regardless_of_policy() {
        // 외부 MCP 도구(builtinNames 에 없음)는 정책 resolve 가 NONE 이어도 API 에 실린다.
        ToolRegistry r = builtinRegistry(List.of(builtinTool("web_search")));
        // 동적 등록(외부 MCP) — register 만 호출, builtinNames 에 안 들어감.
        r.register(externalTool("external_mcp_tool"));

        List<String> names = r.getToolDefs(ToolFilterContext.none()).stream()
                .map(UnifiedToolDef::name).toList();
        assertThat(names).contains("web_search", "external_mcp_tool");
    }

    @Test
    void null_filter_still_applies_exposure_policy() {
        ToolRegistry r = builtinRegistry(List.of(builtinTool("web_search"), builtinTool("parse_document")));
        List<String> names = r.getToolDefs(null).stream().map(UnifiedToolDef::name).toList();
        assertThat(names).containsExactly("web_search");
    }

    /** builtins 를 넣고 registerBuiltins 를 태워 builtinNames 를 채운 레지스트리. */
    private ToolRegistry builtinRegistry(List<ToolExecutor> builtins) {
        ToolRegistry r = new ToolRegistry(builtins, mock(PlatformMetrics.class), policy);
        r.registerBuiltins();
        return r;
    }

    private static ToolExecutor builtinTool(String name) {
        return externalTool(name);
    }

    private static ToolExecutor externalTool(String name) {
        UnifiedToolDef def = new UnifiedToolDef(name, "test", Map.of("type", "object"));
        return new ToolExecutor() {
            @Override
            public UnifiedToolDef getDefinition() { return def; }

            @Override
            public String execute(Map<String, Object> args) { return "{}"; }
        };
    }
}
