package com.platform.mcp.server;

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

        new ServerMcpConfig(toolRegistry, dispatcher, ctx).syncExposedTools();

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

        new ServerMcpConfig(toolRegistry, mock(ServerMcpToolDispatcher.class), ctx).syncExposedTools();

        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> all =
                ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(mcpServer, times(4)).addTool(all.capture());
        ArgumentCaptor<McpServerFeatures.SyncToolSpecification> skills =
                ArgumentCaptor.forClass(McpServerFeatures.SyncToolSpecification.class);
        verify(skillServer, times(3)).addTool(skills.capture());
        assertThat(skills.getAllValues()).extracting(s -> s.tool().name())
                .containsExactlyInAnyOrder("skill_open", "skill_read", "skill_run");
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
