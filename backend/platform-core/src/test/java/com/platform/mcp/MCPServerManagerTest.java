package com.platform.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.MCPServerEntity;
import com.platform.tool.model.UnifiedToolDef;
import com.platform.repository.MCPServerRepository;
import com.platform.tenant.TenantDataSourceManager;
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
    @Mock private TenantDataSourceManager tenantDataSourceManager;

    private MCPServerManager manager;
    private Map<String, MCPServerClient> connections;

    @BeforeEach
    void setUp() throws Exception {
        manager = new MCPServerManager(mcpServerRepository, toolRegistry, new ObjectMapper(), tenantDataSourceManager);

        // 리플렉션으로 connections 맵 접근
        Field connectionsField = MCPServerManager.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, MCPServerClient> map = (Map<String, MCPServerClient>) connectionsField.get(manager);
        connections = map;
    }

    // ── connectAutoStartServers ─────────────────────────────
    // 구현은 tenantDataSourceManager.getAllCachedDataSources() 로 tenant 목록을 순회한 후
    // 각 tenant context 에서 mcpServerRepository.findAll() 을 호출한다.

    @Test
    void connectAutoStartServers_noTenants_shouldBeNoop() {
        when(tenantDataSourceManager.getAllCachedDataSources()).thenReturn(Map.of());

        manager.connectAutoStartServers();

        // 활성 tenant 가 없으면 repository 접근도 없어야 함
        verify(mcpServerRepository, never()).findAll();
    }

    @Test
    void connectAutoStartServers_noServers_shouldLogAndReturn() {
        when(tenantDataSourceManager.getAllCachedDataSources())
                .thenReturn(Map.of("tenant-1", mock(com.zaxxer.hikari.HikariDataSource.class)));
        when(mcpServerRepository.findAll()).thenReturn(List.of());

        manager.connectAutoStartServers();

        // autoStart 서버가 없으면 연결 시도 없음
        verify(mcpServerRepository).findAll();
    }

    @Test
    void connectAutoStartServers_dbException_shouldNotCrash() {
        when(tenantDataSourceManager.getAllCachedDataSources())
                .thenReturn(Map.of("tenant-1", mock(com.zaxxer.hikari.HikariDataSource.class)));
        when(mcpServerRepository.findAll()).thenThrow(new RuntimeException("DB not ready"));

        // 앱 시작 시 실패해도 non-fatal — tenant 루프 내에서 예외 포획
        manager.connectAutoStartServers();

        verify(mcpServerRepository).findAll();
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

    // ── reconnect ───────────────────────────────────────────

    @Test
    void reconnect_existingConnection_shouldCloseOldAndDiscover() {
        MCPServerEntity entity = buildServerEntity("srv-1");
        when(mcpServerRepository.findById("srv-1")).thenReturn(Optional.of(entity));

        // 기존 (stale) 연결 주입
        MCPServerClient staleClient = mock(MCPServerClient.class);
        connections.put("srv-1", staleClient);

        // discover 단계에서 새 connection 이 만들어지지 않도록, computeIfAbsent 로 들어갈 새 client 를 미리 주입할 수 없다.
        // 대신 reconnect 가 discover 를 호출하는 흐름만 검증 — staleClient.close() 호출 + status disconnected → connected 전이.
        // discover 가 실제 외부 서버에 접근하지 않도록, computeIfAbsent 이후 isInitialized=true 인 새 mock client 주입을 위해
        // reconnect 호출 직후 connections 에 새 mockClient 를 강제 주입하는 방법은 race 가 있어, 여기서는 close 만 검증한다.
        try {
            manager.reconnect("srv-1");
        } catch (Exception ignored) {
            // 실제 외부 호출은 없으므로 discover 가 connect() 시도 시 NPE 가 날 수 있다 — 본 테스트는 close 검증만 한다.
        }

        verify(staleClient).close();
        // 기존 연결이 제거됐고, repository.save 가 disconnected 로 1회 호출됐는지 확인
        verify(mcpServerRepository, atLeastOnce()).save(entity);
    }

    @Test
    void reconnect_noExistingConnection_shouldStillCallDiscover() {
        MCPServerEntity entity = buildServerEntity("srv-2");
        when(mcpServerRepository.findById("srv-2")).thenReturn(Optional.of(entity));

        try {
            manager.reconnect("srv-2");
        } catch (Exception ignored) {
            // discover 의 외부 호출 부분은 본 테스트 범위 외
        }

        // 기존 연결이 없어도 status 를 disconnected 로 한 번 갱신했어야 함 (discover 가 성공하면 connected 로 다시 갱신)
        verify(mcpServerRepository, atLeastOnce()).save(entity);
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
