package com.platform.mcp.server;

import com.platform.config.PlatformSettingsService;
import com.platform.tool.ToolExecutor;
import com.platform.tool.ToolRegistry;
import com.platform.tool.model.UnifiedToolDef;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerMcpConfigTest {

    @Test
    void 실린_도구의_핸들러는_호출_때_이름으로_실행기를_찾는다() {
        // CR-146: 실을 때의 실행기를 잡아 두면 바깥 MCP 재연결 뒤에도 닫힌 옛 연결을 부른다.
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ServerMcpToolDispatcher dispatcher = mock(ServerMcpToolDispatcher.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        McpSyncServer mcpServer = mock(McpSyncServer.class);
        when(ctx.getBean("serverMcpServer", McpSyncServer.class)).thenReturn(mcpServer);
        when(toolRegistry.isBuiltinsRegistered()).thenReturn(true);
        ToolExecutor first = stubTool("get_opportunity");
        when(toolRegistry.getCliExposedTools()).thenReturn(List.of(first));

        new ServerMcpConfig(toolRegistry, dispatcher, ctx, mock(PlatformSettingsService.class)).syncExposedTools();

        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> spec =
                ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(mcpServer).addTool(spec.capture());

        Map<String, Object> args = Map.of("id", "x");
        spec.getValue().callHandler().apply(null, new McpSchema.CallToolRequest("get_opportunity", args));

        verify(dispatcher).dispatch(eq("get_opportunity"), eq(args));
        verify(dispatcher, never()).dispatch(any(ToolExecutor.class), anyString(), anyMap());
    }

    @Test
    void 스킬_통로에는_스킬_도구만_실린다() {
        // CR-147: NATIVE 는 /mcp-skills 만 받으므로 그 통로에 다른 서버 도구가 섞이면 안 된다(BIZ-123).
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        McpSyncServer mcpServer = mock(McpSyncServer.class);
        McpSyncServer skillServer = mock(McpSyncServer.class);
        when(ctx.getBean("serverMcpServer", McpSyncServer.class)).thenReturn(mcpServer);
        when(ctx.getBean("skillMcpServer", McpSyncServer.class)).thenReturn(skillServer);
        when(toolRegistry.isBuiltinsRegistered()).thenReturn(true);
        when(toolRegistry.getCliExposedTools()).thenReturn(List.of(
                stubTool("get_opportunity"), stubTool("skill_open"), stubTool("skill_read"), stubTool("skill_run")));

        new ServerMcpConfig(toolRegistry, mock(ServerMcpToolDispatcher.class), ctx, mock(PlatformSettingsService.class))
                .syncExposedTools();

        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> all =
                ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(mcpServer, times(4)).addTool(all.capture());
        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> skills =
                ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(skillServer, times(3)).addTool(skills.capture());
        assertThat(skills.getAllValues()).extracting(s -> s.tool().name())
                .containsExactlyInAnyOrder("skill_open", "skill_read", "skill_run");
    }

    @Test
    void cr153_두_통로의_모든_도구에_결과_크기_상한을_싣는다() {
        // TC-MCP-RES-002: 표시가 없으면 CLI 가 큰 결과를 파일로 돌리고 AIMBASE 모드는 그 파일을 못 읽는다.
        ApplicationContext ctx = mock(ApplicationContext.class);
        McpSyncServer mcpServer = mock(McpSyncServer.class);
        McpSyncServer skillServer = mock(McpSyncServer.class);
        when(ctx.getBean("serverMcpServer", McpSyncServer.class)).thenReturn(mcpServer);
        when(ctx.getBean("skillMcpServer", McpSyncServer.class)).thenReturn(skillServer);
        PlatformSettingsService settings = settingsReturning(200_000);

        newConfig(ctx, settings, "get_proposal_design_input", "skill_open").syncExposedTools();

        assertThat(addedTools(mcpServer, 2)).extracting(McpSchema.Tool::meta)
                .containsOnly(Map.of("anthropic/maxResultSizeChars", 200_000));
        assertThat(addedTools(skillServer, 1)).extracting(McpSchema.Tool::meta)
                .containsOnly(Map.of("anthropic/maxResultSizeChars", 200_000));
    }

    @Test
    void cr153_설정이_없거나_0_이하면_기본값을_싣는다() {
        // TC-MCP-RES-003: 숫자가 아닌 값은 PlatformSettingsService.getInt 가 기본값으로 돌려준다.
        for (Integer configured : new Integer[] {null, 0, -1}) {
            ApplicationContext ctx = mock(ApplicationContext.class);
            McpSyncServer mcpServer = mock(McpSyncServer.class);
            when(ctx.getBean("serverMcpServer", McpSyncServer.class)).thenReturn(mcpServer);
            PlatformSettingsService settings = configured == null ? settingsWithoutKey() : settingsReturning(configured);

            newConfig(ctx, settings, "get_opportunity").syncExposedTools();

            assertThat(addedTools(mcpServer, 1)).extracting(McpSchema.Tool::meta)
                    .as("configured=%s", configured)
                    .containsOnly(Map.of("anthropic/maxResultSizeChars", ServerMcpConfig.DEFAULT_RESULT_MAX_CHARS));
        }
    }

    @Test
    void cr153_상한이_바뀌면_도구를_모두_새_값으로_다시_싣는다() {
        // TC-MCP-RES-004: 이름이 같으면 다시 싣지 않으므로 바뀐 값이 CLI 에 닿지 않는다.
        ApplicationContext ctx = mock(ApplicationContext.class);
        McpSyncServer mcpServer = mock(McpSyncServer.class);
        when(ctx.getBean("serverMcpServer", McpSyncServer.class)).thenReturn(mcpServer);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getInt(eq(ServerMcpConfig.RESULT_MAX_CHARS_KEY), anyInt()))
                .thenReturn(200_000, 300_000, 300_000);
        ServerMcpConfig config = newConfig(ctx, settings, "get_opportunity", "get_proposal_design_input");

        config.syncExposedTools();
        config.syncExposedTools();
        config.syncExposedTools();

        verify(mcpServer).removeTool("get_opportunity");
        verify(mcpServer).removeTool("get_proposal_design_input");
        List<McpSchema.Tool> added = addedTools(mcpServer, 4);
        assertThat(added.subList(2, 4)).extracting(McpSchema.Tool::meta)
                .containsOnly(Map.of("anthropic/maxResultSizeChars", 300_000));
        verify(mcpServer, times(2)).notifyToolsListChanged();
    }

    private static ServerMcpConfig newConfig(ApplicationContext ctx, PlatformSettingsService settings, String... tools) {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.isBuiltinsRegistered()).thenReturn(true);
        when(toolRegistry.getCliExposedTools()).thenReturn(
                java.util.Arrays.stream(tools).map(ServerMcpConfigTest::stubTool).toList());
        return new ServerMcpConfig(toolRegistry, mock(ServerMcpToolDispatcher.class), ctx, settings);
    }

    private static PlatformSettingsService settingsReturning(int value) {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getInt(eq(ServerMcpConfig.RESULT_MAX_CHARS_KEY), anyInt())).thenReturn(value);
        return settings;
    }

    private static PlatformSettingsService settingsWithoutKey() {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getInt(eq(ServerMcpConfig.RESULT_MAX_CHARS_KEY), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
        return settings;
    }

    private static List<McpSchema.Tool> addedTools(McpSyncServer server, int expected) {
        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> spec =
                ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(server, times(expected)).addTool(spec.capture());
        return spec.getAllValues().stream().map(McpServerFeatures.SyncToolSpecification::tool).toList();
    }

    private static ToolExecutor stubTool(String name) {
        UnifiedToolDef def = new UnifiedToolDef(name, "test", Map.of("type", "object"));
        return new ToolExecutor() {
            @Override
            public UnifiedToolDef getDefinition() { return def; }

            @Override
            public String execute(Map<String, Object> args) { return "unused"; }
        };
    }
}
