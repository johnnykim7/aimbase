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
        // 메타가 기본 NONE 이면 매핑 결과 사용 (CLI 노출 중 하나)
        ToolExecutor exec = enhancedTool("web_search", McpExposureLevel.NONE);
        assertThat(McpExposurePolicy.resolve(exec)).isEqualTo(McpExposureLevel.CLI);
    }

    @Test
    void cr104_workflow_essential_tools_are_cli_exposed() {
        // CR-104: 워크플로우 필수 native/유틸 도구가 CLI 경로에도 노출되어야 한다.
        // (이전에는 NONE 폴백 → CLI 에 안 보여 "No such tool" 회귀의 원인이었음)
        List<String> nowExposed = List.of(
                "bash", "file_write", "builtin_grep", "builtin_file_read", "builtin_glob",
                "builtin_safe_edit", "builtin_patch_apply", "builtin_structured_search",
                "builtin_document_section_read", "builtin_path_info", "builtin_workspace_snapshot",
                "zip_extract", "calculate", "get_current_time");
        for (String name : nowExposed) {
            assertThat(McpExposurePolicy.isCliExposed(legacyTool(name)))
                    .as("CR-104: '%s' must be CLI-exposed (API 경로와 동일 집합)", name)
                    .isTrue();
        }
    }

    @Test
    void cr104_internal_only_tools_remain_NONE() {
        // CR-104 불변식 예외: 내부 관리/계획 전용 6개는 여전히 CLI 미노출.
        List<String> stillNone = List.of(
                "team_create", "team_delete",
                "enter_plan_mode", "exit_plan_mode", "verify_plan_execution",
                "temp_cleanup");
        for (String name : stillNone) {
            assertThat(McpExposurePolicy.isCliExposed(legacyTool(name)))
                    .as("CR-104: '%s' must remain internal-only (NONE)", name)
                    .isFalse();
        }
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
