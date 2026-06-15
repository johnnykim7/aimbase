package com.platform.mcp.server;

import com.platform.config.PlatformSettingsService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-110: McpExposurePolicy 가 설정 기반 빈으로 전환됨에 따라 재작성.
 * PlatformSettingsService 를 목 주입하여 화이트리스트를 제어한다.
 */
class McpExposurePolicyTest {

    private PlatformSettingsService settings;
    private McpExposurePolicy policy;

    @BeforeEach
    void setUp() {
        settings = mock(PlatformSettingsService.class);
        // 기본: 설정 row 있고 web_search/bash 만 노출 (테스트별로 override)
        when(settings.getStringList(eq(McpExposurePolicy.SETTING_KEY), any()))
                .thenReturn(List.of("web_search", "bash"));
        policy = new McpExposurePolicy(settings);
    }

    @Test
    void resolves_setting_listed_tool_to_CLI() {
        ToolExecutor exec = legacyTool("web_search");
        assertThat(policy.resolve(exec)).isEqualTo(McpExposureLevel.CLI);
        assertThat(policy.isCliExposed(exec)).isTrue();
    }

    @Test
    void resolves_setting_unlisted_tool_to_NONE() {
        ToolExecutor exec = legacyTool("team_create");
        assertThat(policy.resolve(exec)).isEqualTo(McpExposureLevel.NONE);
        assertThat(policy.isCliExposed(exec)).isFalse();
    }

    @Test
    void resolves_unknown_tool_to_NONE_default() {
        assertThat(policy.resolve(legacyTool("unknown_tool_xyz"))).isEqualTo(McpExposureLevel.NONE);
    }

    @Test
    void parse_document_is_NONE_regression_guard() {
        // CR-110 핵심: parse_document 는 양쪽 도구 목록에서 의도적으로 제외 — 설정에 없으므로 NONE.
        assertThat(policy.isCliExposed(legacyTool("parse_document"))).isFalse();
    }

    @Test
    void contract_meta_explicit_CLI_overrides_setting() {
        // 설정상 미노출이라도 메타가 CLI 명시하면 CLI 우선 (1순위 메커니즘 보존).
        ToolExecutor exec = enhancedTool("some_future_tool", McpExposureLevel.CLI);
        assertThat(policy.resolve(exec)).isEqualTo(McpExposureLevel.CLI);
    }

    @Test
    void contract_meta_NONE_falls_through_to_setting() {
        // 메타가 기본 NONE 이면 설정 화이트리스트 결과 사용.
        ToolExecutor exec = enhancedTool("web_search", McpExposureLevel.NONE);
        assertThat(policy.resolve(exec)).isEqualTo(McpExposureLevel.CLI);
    }

    @Test
    void blank_setting_falls_back_to_DEFAULT_FALLBACK() {
        // DB row 부재/blank → getStringList 가 defaultValue(=DEFAULT_FALLBACK) 반환 (fail-safe).
        // getStringList 동작을 그대로 흉내: 빈 값이면 두번째 인자를 그대로 반환.
        when(settings.getStringList(eq(McpExposurePolicy.SETTING_KEY), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        // DEFAULT_FALLBACK 에 포함된 도구는 CLI, parse_document/내부전용은 NONE.
        assertThat(policy.isCliExposed(legacyTool("web_search"))).isTrue();
        assertThat(policy.isCliExposed(legacyTool("bash"))).isTrue();
        assertThat(policy.isCliExposed(legacyTool("parse_document"))).isFalse();
        assertThat(policy.isCliExposed(legacyTool("team_create"))).isFalse();
    }

    @Test
    void default_fallback_matches_42_tools_excluding_parse_document() {
        // V66 seed CSV 와 동일 집합이어야 함(환경별 도구집합 갈림 방지). 개수 + parse_document 부재 고정.
        assertThat(McpExposurePolicy.DEFAULT_FALLBACK).hasSize(42);
        assertThat(McpExposurePolicy.DEFAULT_FALLBACK).doesNotContain("parse_document");
        assertThat(McpExposurePolicy.DEFAULT_FALLBACK)
                .doesNotContain("team_create", "team_delete", "enter_plan_mode",
                        "exit_plan_mode", "verify_plan_execution", "temp_cleanup");
        assertThat(McpExposurePolicy.DEFAULT_FALLBACK)
                .contains("web_search", "bash", "file_write", "builtin_grep", "ocr_image");
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
