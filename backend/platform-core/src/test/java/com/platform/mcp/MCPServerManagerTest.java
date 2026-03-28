package com.platform.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.MCPServerEntity;
import com.platform.llm.model.UnifiedToolDef;
import com.platform.repository.MCPServerRepository;
import com.platform.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MCPServerManager 단위 테스트.
 * MCPServerClient는 외부 MCP 서버에 의존하므로 리플렉션으로 Mock 주입.
 */
@ExtendWith(MockitoExtension.class)
class MCPServerManagerTest {

    @Mock private MCPServerRepository mcpServerRepository;
    @Mock private ToolRegistry toolRegistry;
    @Mock private MCPServerClient mockClient;

    private MCPServerManager manager;
    private Map<String, MCPServerClient> connections;

    @BeforeEach
    void setUp() throws Exception {
        manager = new MCPServerManager(mcpServerRepository, toolRegistry, new ObjectMapper());

        // 리플렉션으로 connections 맵 접근
        Field connectionsField = MCPServerManager.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, MCPServerClient> map = (Map<String, MCPServerClient>) connectionsField.get(manager);
        connections = map;
    }

    // ── connectAutoStartServers ─────────────────────────────

    @Test
    void connectAutoStartServers_noServers_shouldLogAndReturn() {
        when(mcpServerRepository.findAll()).thenReturn(List.of());

        manager.connectAutoStartServers();

        // autoStart 서버가 없으면 연결 시도 없음
        verify(mcpServerRepository).findAll();
    }

    @Test
    void connectAutoStartServers_dbException_shouldNotCrash() {
        when(mcpServerRepository.findAll()).thenThrow(new RuntimeException("DB not ready"));

        // 앱 시작 시 실패해도 non-fatal
        manager.connectAutoStartServers();
    }

    // ── discover ────────────────────────────────────────────

    @Test
    void discover_serverNotFound_shouldThrow() {
        when(mcpServerRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> manager.discover("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP server not found");
    }

    @Test
    void discover_withExistingConnection_shouldReuseAndRegisterTools() {
        MCPServerEntity entity = buildServerEntity("srv-1");
        when(mcpServerRepository.findById("srv-1")).thenReturn(Optional.of(entity));

        // 기존 연결 주입
        connections.put("srv-1", mockClient);
        when(mockClient.isInitialized()).thenReturn(true);

        UnifiedToolDef tool1 = new UnifiedToolDef("tool_a", "desc_a", Map.of());
        UnifiedToolDef tool2 = new UnifiedToolDef("tool_b", "desc_b", Map.of());
        when(mockClient.discoverTools()).thenReturn(List.of(tool1, tool2));

        List<UnifiedToolDef> result = manager.discover("srv-1");

        assertThat(result).hasSize(2);
        verify(mockClient, never()).connect(); // 이미 초기화됨
        verify(toolRegistry, times(2)).register(any(MCPToolExecutor.class));
        verify(mcpServerRepository).save(entity);
        assertThat(entity.getStatus()).isEqualTo("connected");
    }

    @Test
    void discover_notInitialized_shouldCallConnect() {
        MCPServerEntity entity = buildServerEntity("srv-2");
        when(mcpServerRepository.findById("srv-2")).thenReturn(Optional.of(entity));

        connections.put("srv-2", mockClient);
        when(mockClient.isInitialized()).thenReturn(false);
        when(mockClient.discoverTools()).thenReturn(List.of());

        manager.discover("srv-2");

        verify(mockClient).connect();
    }

    @Test
    void discover_dbSaveFails_shouldStillRegisterTools() {
        MCPServerEntity entity = buildServerEntity("srv-3");
        when(mcpServerRepository.findById("srv-3")).thenReturn(Optional.of(entity));

        connections.put("srv-3", mockClient);
        when(mockClient.isInitialized()).thenReturn(true);

        UnifiedToolDef tool = new UnifiedToolDef("tool_x", "desc", Map.of());
        when(mockClient.discoverTools()).thenReturn(List.of(tool));
        when(mcpServerRepository.save(any())).thenThrow(new RuntimeException("DB error"));

        // DB 저장 실패해도 도구 등록은 진행
        List<UnifiedToolDef> result = manager.discover("srv-3");

        assertThat(result).hasSize(1);
        verify(toolRegistry).register(any(MCPToolExecutor.class));
    }

    // ── disconnect ──────────────────────────────────────────

    @Test
    void disconnect_existingConnection_shouldCloseAndUnregister() {
        connections.put("srv-1", mockClient);

        UnifiedToolDef tool = new UnifiedToolDef("tool_a", "desc", Map.of());
        when(mockClient.discoverTools()).thenReturn(List.of(tool));

        MCPServerEntity entity = buildServerEntity("srv-1");
        when(mcpServerRepository.findById("srv-1")).thenReturn(Optional.of(entity));

        manager.disconnect("srv-1");

        verify(toolRegistry).unregister("tool_a");
        verify(mockClient).close();
        verify(mcpServerRepository).save(entity);
        assertThat(entity.getStatus()).isEqualTo("disconnected");
        assertThat(connections).doesNotContainKey("srv-1");
    }

    @Test
    void disconnect_noConnection_shouldDoNothing() {
        manager.disconnect("non-existent");

        verify(mcpServerRepository, never()).save(any());
    }

    @Test
    void disconnect_toolListFails_shouldStillClose() {
        connections.put("srv-1", mockClient);
        when(mockClient.discoverTools()).thenThrow(new RuntimeException("connection lost"));
        when(mcpServerRepository.findById("srv-1")).thenReturn(Optional.of(buildServerEntity("srv-1")));

        manager.disconnect("srv-1");

        verify(mockClient).close();
    }

    // ── getConnectedServerIds ───────────────────────────────

    @Test
    void getConnectedServerIds_shouldReturnCurrentKeys() {
        connections.put("a", mockClient);
        connections.put("b", mockClient);

        List<String> ids = manager.getConnectedServerIds();

        assertThat(ids).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void getConnectedServerIds_empty_shouldReturnEmpty() {
        assertThat(manager.getConnectedServerIds()).isEmpty();
    }

    // ── Helper ──────────────────────────────────────────────

    private MCPServerEntity buildServerEntity(String id) {
        MCPServerEntity entity = new MCPServerEntity();
        entity.setId(id);
        entity.setName("Test Server " + id);
        entity.setTransport("sse");
        entity.setConfig(Map.of("url", "http://localhost:9999/mcp"));
        entity.setAutoStart(true);
        entity.setStatus("disconnected");
        return entity;
    }
}
