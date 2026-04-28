package com.platform.mcp.server;

import com.platform.tool.EnhancedToolExecutor;
import com.platform.tool.McpExposureLevel;
import com.platform.tool.PermissionLevel;
import com.platform.tool.RetryPolicy;
import com.platform.tool.ToolContext;
import com.platform.tool.ToolContractMeta;
import com.platform.tool.ToolExecutor;
import com.platform.tool.ToolResult;
import com.platform.tool.ToolScope;
import com.platform.tool.model.UnifiedToolDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpExposurePolicyTest {

    @Test
    void resolves_known_cli_tool_to_CLI() {
        ToolExecutor exec = legacyTool("web_search");
        assertThat(McpExposurePolicy.resolve(exec)).isEqualTo(McpExposureLevel.CLI);
        assertThat(McpExposurePolicy.isCliExposed(exec)).isTrue();
    }

    @Test
    void resolves_known_internal_tool_to_NONE() {
        ToolExecutor exec = legacyTool("team_create");
        assertThat(McpExposurePolicy.resolve(exec)).isEqualTo(McpExposureLevel.NONE);
        assertThat(McpExposurePolicy.isCliExposed(exec)).isFalse();
    }

    @Test
    void resolves_unknown_tool_to_NONE_default() {
        ToolExecutor exec = legacyTool("unknown_tool_xyz");
        assertThat(McpExposurePolicy.resolve(exec)).isEqualTo(McpExposureLevel.NONE);
    }

    @Test
    void contract_meta_explicit_level_overrides_mapping() {
        // 매핑상 NONE 인 도구라도 메타가 CLI 명시하면 CLI 우선
        ToolExecutor exec = enhancedTool("team_create", McpExposureLevel.CLI);
        assertThat(McpExposurePolicy.resolve(exec)).isEqualTo(McpExposureLevel.CLI);
    }

    @Test
    void contract_meta_NONE_falls_through_to_mapping() {
        // 메타가 기본 NONE 이면 매핑 결과 사용 (CLI 노출 26개 중 하나)
        ToolExecutor exec = enhancedTool("web_search", McpExposureLevel.NONE);
        assertThat(McpExposurePolicy.resolve(exec)).isEqualTo(McpExposureLevel.CLI);
    }

    private static ToolExecutor legacyTool(String name) {
        UnifiedToolDef def = new UnifiedToolDef(name, "test", Map.of("type", "object"));
        return new ToolExecutor() {
            @Override
            public UnifiedToolDef getDefinition() { return def; }

            @Override
            public String execute(Map<String, Object> args) { return "{}"; }
        };
    }

    private static ToolExecutor enhancedTool(String name, McpExposureLevel level) {
        UnifiedToolDef def = new UnifiedToolDef(name, "test", Map.of("type", "object"));
        ToolContractMeta meta = new ToolContractMeta(
                name, "1.0", ToolScope.BUILTIN, PermissionLevel.READ_ONLY,
                false, true, false, true,
                RetryPolicy.NONE, List.of(), List.of(),
                level
        );
        return new EnhancedToolExecutor() {
            @Override
            public UnifiedToolDef getDefinition() { return def; }

            @Override
            public ToolContractMeta getContractMeta() { return meta; }

            @Override
            public ToolResult execute(Map<String, Object> input, ToolContext ctx) {
                return ToolResult.ok("done", "ok");
            }
        };
    }
}
