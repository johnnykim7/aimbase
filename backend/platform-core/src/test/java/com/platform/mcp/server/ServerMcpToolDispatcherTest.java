package com.platform.mcp.server;

import com.platform.hook.HookDispatcher;
import com.platform.hook.HookEvent;
import com.platform.hook.HookOutput;
import com.platform.policy.TokenBucketRateLimiter;
import com.platform.tool.ToolExecutor;
import com.platform.tool.model.UnifiedToolDef;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerMcpToolDispatcherTest {

    private HookDispatcher hookDispatcher;
    private TokenBucketRateLimiter rateLimiter;
    private ServerMcpToolDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        hookDispatcher = mock(HookDispatcher.class);
        rateLimiter = mock(TokenBucketRateLimiter.class);
        // 기본: PASSTHROUGH 반환 (BLOCK 아님)
        when(hookDispatcher.dispatch(any(HookEvent.class), any(), any(String.class)))
                .thenReturn(HookOutput.PASSTHROUGH);
        // 기본: rate limit 통과
        when(rateLimiter.tryAcquire(anyString(), anyInt()))
                .thenReturn(TokenBucketRateLimiter.RateLimitResult.allowed(60, 59));
        dispatcher = new ServerMcpToolDispatcher(hookDispatcher, rateLimiter, 60);
    }

    @Test
    void rejects_non_cli_exposed_tool() {
        ToolExecutor tool = stubTool("team_create", "{\"ok\":true}");

        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "team_create", Map.of());

        assertThat(result.isError()).isTrue();
        // PRE_TOOL_USE Hook 도 호출 안 됨
        verify(hookDispatcher, times(0)).dispatch(eq(HookEvent.PRE_TOOL_USE), any(), any(String.class));
    }

    @Test
    void cli_exposed_tool_executes_with_pre_post_hooks() {
        ToolExecutor tool = stubTool("web_search", "{\"results\":[]}");

        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "web_search", Map.of("q", "test"));

        assertThat(result.isError()).isFalse();
        verify(hookDispatcher, times(1)).dispatch(eq(HookEvent.PRE_TOOL_USE), any(), eq("web_search"));
        verify(hookDispatcher, times(1)).dispatch(eq(HookEvent.POST_TOOL_USE), any(), eq("web_search"));
    }

    @Test
    void pre_hook_block_aborts_execution() {
        when(hookDispatcher.dispatch(eq(HookEvent.PRE_TOOL_USE), any(), eq("web_search")))
                .thenReturn(HookOutput.block("denied"));

        ToolExecutor tool = stubTool("web_search", "should-not-run");
        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "web_search", Map.of());

        assertThat(result.isError()).isTrue();
        // POST_TOOL_USE 는 호출 안 됨
        verify(hookDispatcher, times(0)).dispatch(eq(HookEvent.POST_TOOL_USE), any(), any(String.class));
    }

    @Test
    void rate_limit_exceeded_aborts_execution() {
        when(rateLimiter.tryAcquire(anyString(), anyInt()))
                .thenReturn(TokenBucketRateLimiter.RateLimitResult.exceeded(60, 100, 30));

        ToolExecutor tool = stubTool("web_search", "should-not-run");
        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "web_search", Map.of());

        assertThat(result.isError()).isTrue();
        // Hook 도 호출 안 됨 (rate limit 가 가장 먼저)
        verify(hookDispatcher, times(0)).dispatch(eq(HookEvent.PRE_TOOL_USE), any(), any(String.class));
    }

    @Test
    void execution_failure_dispatches_post_failure_hook() {
        ToolExecutor tool = new ToolExecutor() {
            @Override
            public UnifiedToolDef getDefinition() {
                return new UnifiedToolDef("web_search", "test", Map.of("type", "object"));
            }

            @Override
            public String execute(Map<String, Object> args) {
                throw new RuntimeException("boom");
            }
        };

        McpSchema.CallToolResult result = dispatcher.dispatch(tool, "web_search", Map.of());

        assertThat(result.isError()).isTrue();
        verify(hookDispatcher, times(1)).dispatch(eq(HookEvent.POST_TOOL_USE_FAILURE), any(), eq("web_search"));
        // 성공 Hook 은 호출 안 됨
        verify(hookDispatcher, times(0)).dispatch(eq(HookEvent.POST_TOOL_USE), any(), any(String.class));
    }

    private static ToolExecutor stubTool(String name, String result) {
        UnifiedToolDef def = new UnifiedToolDef(name, "test", Map.of("type", "object"));
        return new ToolExecutor() {
            @Override
            public UnifiedToolDef getDefinition() { return def; }

            @Override
            public String execute(Map<String, Object> args) { return result; }
        };
    }
}
