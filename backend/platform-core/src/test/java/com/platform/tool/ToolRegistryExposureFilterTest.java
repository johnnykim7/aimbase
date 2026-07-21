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

    /**
     * CR-121 회귀 가드: 원격 에이전트 도구(RemoteToolDiscovery 가 동적 register)는 화이트리스트에
     * 없어도 CLI 채널에 노출된다. 이전 구현은 CLI 경로가 화이트리스트만 봐서 소비앱 도구가
     * /mcp/sse 에 영영 실리지 않았다.
     */
    @Test
    void remote_tool_is_cli_exposed_without_whitelist() {
        ToolRegistry r = builtinRegistry(List.of(builtinTool("web_search")));
        ToolExecutor remote = externalTool("wes_pick_order");
        r.register(remote);

        assertThat(r.isCliExposed(remote)).isTrue();
        assertThat(r.getCliExposedTools().stream().map(t -> t.getDefinition().name()).toList())
                .contains("wes_pick_order", "web_search");
    }

    /** CR-121: builtin 은 여전히 화이트리스트 통제를 받는다 — 자동 노출이 builtin 까지 번지면 안 된다. */
    @Test
    void builtin_still_gated_by_whitelist_for_cli() {
        ToolRegistry r = builtinRegistry(List.of(builtinTool("web_search"), builtinTool("parse_document")));

        assertThat(r.getCliExposedTools().stream().map(t -> t.getDefinition().name()).toList())
                .containsExactly("web_search");
    }

    /** CR-121: CLI 노출 집합과 API 도구 집합이 동일하다는 CR-104/110 불변식(원격 도구 포함). */
    @Test
    void cli_exposed_set_equals_api_tool_set() {
        ToolRegistry r = builtinRegistry(List.of(builtinTool("web_search"), builtinTool("parse_document")));
        r.register(externalTool("wes_pick_order"));

        List<String> cli = r.getCliExposedTools().stream().map(t -> t.getDefinition().name()).sorted().toList();
        List<String> api = r.getToolDefs(ToolFilterContext.none()).stream()
                .map(UnifiedToolDef::name).sorted().toList();
        assertThat(cli).isEqualTo(api);
    }

    /**
     * CR-121 회귀 가드: registerBuiltins 전에는 builtinNames 가 비어 모든 도구가 "외부 도구"로
     * 오판된다(화이트리스트 우회). 소비자가 이 상태를 구분할 수 있어야 MCP 노출을 미룰 수 있다.
     *
     * <p>운영 실측: ServerMcpConfig 가 ToolRegistry 보다 6ms 먼저 돌아 parse_document 등
     * 비노출 대상이 /mcp/sse 에 실렸다.</p>
     */
    @Test
    void builtins_not_registered_flag_signals_unreliable_exposure() {
        ToolRegistry r = new ToolRegistry(List.of(builtinTool("parse_document")),
                mock(PlatformMetrics.class), policy);

        // 아직 registerBuiltins 전 — 플래그로 판정 불가 상태임을 알 수 있어야 한다.
        assertThat(r.isBuiltinsRegistered()).isFalse();

        r.registerBuiltins();
        assertThat(r.isBuiltinsRegistered()).isTrue();
        // 등록 후에는 화이트리스트 통제가 정상 작동 (parse_document 는 정책상 NONE).
        assertThat(r.getCliExposedTools()).isEmpty();
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
