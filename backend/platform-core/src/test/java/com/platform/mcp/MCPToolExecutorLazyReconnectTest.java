package com.platform.mcp;

import com.platform.tool.model.UnifiedToolDef;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * MCPToolExecutor 의 lazy reconnect 동작 단위 테스트.
 *
 * 동작 규약:
 * - 첫 callTool 실패 시 manager.reconnect(serverId) 호출
 * - reconnect 가 성공하면 manager.getClient(serverId) 로 새 client 를 얻어 1회 재시도
 * - reconnect 가 실패하거나 새 client 가 null 이면 원본 예외를 전파
 * - manager 또는 serverId 가 null(레거시 생성자) 이면 재시도 없음
 */
class MCPToolExecutorLazyReconnectTest {

    private final UnifiedToolDef def =
            new UnifiedToolDef("get_opportunity", "테스트 도구", Map.of("type", "object"));

    @Test
    void 호출_성공시_재연결_없음() {
        MCPServerClient client = mock(MCPServerClient.class);
        MCPServerManager manager = mock(MCPServerManager.class);
        when(client.callTool(eq("get_opportunity"), any())).thenReturn("ok");

        MCPToolExecutor exec = new MCPToolExecutor(client, def, manager, "srv-1");
        String result = exec.execute(Map.of("id", "x"));

        assertThat(result).isEqualTo("ok");
        verifyNoInteractions(manager);
    }

    @Test
    void 호출_실패시_재연결_후_재시도_성공() {
        MCPServerClient stale = mock(MCPServerClient.class);
        MCPServerClient fresh = mock(MCPServerClient.class);
        MCPServerManager manager = mock(MCPServerManager.class);

        when(stale.callTool(any(), any())).thenThrow(new RuntimeException("connection closed"));
        when(fresh.callTool(eq("get_opportunity"), any())).thenReturn("ok-after-reconnect");
        when(manager.getClient("srv-1")).thenReturn(fresh);

        MCPToolExecutor exec = new MCPToolExecutor(stale, def, manager, "srv-1");
        String result = exec.execute(Map.of("id", "x"));

        assertThat(result).isEqualTo("ok-after-reconnect");
        verify(manager).reconnect("srv-1");
        verify(fresh).callTool(eq("get_opportunity"), any());
    }

    @Test
    void 재연결_실패시_원본_예외_전파() {
        MCPServerClient stale = mock(MCPServerClient.class);
        MCPServerManager manager = mock(MCPServerManager.class);

        when(stale.callTool(any(), any())).thenThrow(new RuntimeException("conn closed"));
        when(manager.reconnect("srv-1")).thenThrow(new RuntimeException("reconnect failed"));

        MCPToolExecutor exec = new MCPToolExecutor(stale, def, manager, "srv-1");

        assertThatThrownBy(() -> exec.execute(Map.of("id", "x")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("conn closed");
    }

    @Test
    void 재연결_후_getClient_null_이면_원본_예외() {
        MCPServerClient stale = mock(MCPServerClient.class);
        MCPServerManager manager = mock(MCPServerManager.class);

        when(stale.callTool(any(), any())).thenThrow(new RuntimeException("conn closed"));
        when(manager.getClient("srv-1")).thenReturn(null);

        MCPToolExecutor exec = new MCPToolExecutor(stale, def, manager, "srv-1");

        assertThatThrownBy(() -> exec.execute(Map.of("id", "x")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("conn closed");
    }

    @Test
    void manager_null_레거시_생성자는_재시도_없음() {
        MCPServerClient stale = mock(MCPServerClient.class);
        when(stale.callTool(any(), any())).thenThrow(new RuntimeException("conn closed"));

        MCPToolExecutor exec = new MCPToolExecutor(stale, def); // legacy ctor

        assertThatThrownBy(() -> exec.execute(Map.of("id", "x")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("conn closed");
    }
}
