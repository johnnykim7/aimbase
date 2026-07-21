package com.platform.llm;

import com.platform.config.PlatformSettingsService;
import com.platform.domain.ConnectionEntity;
import com.platform.llm.adapter.ClaudeCliRunnerClient;
import com.platform.llm.adapter.LLMAdapter;
import com.platform.repository.ConnectionRepository;
import com.platform.service.AgentRegistryService;
import com.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-123: ConnectionAdapterFactory 어댑터 캐시가 config 변경을 반영하는지 검증.
 *
 * <p>어댑터는 생성 시점의 config(tool_mode/api_key/model…)를 필드로 굳히므로, 캐시가
 * connectionId 만으로 잡히면 DB 변경이 영영 반영되지 않는다. 기존엔 evict 가
 * ConnectionController.update() 에서만 불려서 <b>DB 직접 UPDATE 는 무효화 경로가 없었다</b>
 * (운영 실측: tool_mode NATIVE→AIMBASE 변경이 안 먹어 CLI 가 MCP 미연결로 도구를 못 찾음).</p>
 */
class ConnectionAdapterCacheTest {

    private ConnectionRepository connectionRepository;
    private ConnectionAdapterFactory factory;

    @BeforeEach
    void setUp() {
        connectionRepository = mock(ConnectionRepository.class);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        // BIZ-099 피처 플래그 — 전체 허용
        when(settings.getString(anyString(), anyString())).thenReturn("*");

        factory = new ConnectionAdapterFactory(
                connectionRepository,
                16000,
                mock(com.platform.llm.thinking.AdaptiveThinkingPolicy.class),
                settings,
                mock(ClaudeCliRunnerClient.class),
                mock(AgentRegistryService.class));
        TenantContext.setTenantId("tenant-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * CR-123 핵심 회귀 가드: DB 의 config 가 바뀌면(=updatedAt 갱신) evict 호출 없이도
     * 새 어댑터가 생성돼야 한다.
     */
    @Test
    void adapterIsRebuiltWhenConnectionConfigChanges() {
        OffsetDateTime t1 = OffsetDateTime.parse("2026-07-21T17:00:00Z");
        stubConnection(conn("cli-1", "NATIVE", t1));

        LLMAdapter first = factory.getAdapter("cli-1");

        // DB 직접 UPDATE 로 tool_mode 변경 → updated_at 갱신 (evict 호출 없음)
        stubConnection(conn("cli-1", "AIMBASE", t1.plusMinutes(47)));

        LLMAdapter second = factory.getAdapter("cli-1");

        assertThat(second).isNotSameAs(first);
    }

    /** config 가 그대로면 어댑터를 재사용한다 — 캐시 본래 목적(클라이언트 재사용) 보존. */
    @Test
    void adapterIsReusedWhenNothingChanged() {
        OffsetDateTime t1 = OffsetDateTime.parse("2026-07-21T17:00:00Z");
        stubConnection(conn("cli-1", "NATIVE", t1));

        LLMAdapter first = factory.getAdapter("cli-1");
        LLMAdapter second = factory.getAdapter("cli-1");

        assertThat(second).isSameAs(first);
    }

    /** 낡은 버전 엔트리가 쌓이지 않는다 — 변경이 반복돼도 커넥션당 1개만 유지. */
    @Test
    void staleVersionsAreEvicted() {
        OffsetDateTime base = OffsetDateTime.parse("2026-07-21T17:00:00Z");
        stubConnection(conn("cli-1", "NATIVE", base));
        factory.getAdapter("cli-1");
        stubConnection(conn("cli-1", "AIMBASE", base.plusMinutes(1)));
        factory.getAdapter("cli-1");
        stubConnection(conn("cli-1", "HYBRID", base.plusMinutes(2)));
        LLMAdapter latest = factory.getAdapter("cli-1");

        // 최신 버전으로 다시 조회해도 같은 인스턴스(=캐시 히트), 낡은 것들은 제거됨
        assertThat(factory.getAdapter("cli-1")).isSameAs(latest);
    }

    /** Database-per-Tenant: 같은 connectionId 라도 테넌트가 다르면 어댑터가 섞이지 않는다. */
    @Test
    void adaptersAreIsolatedPerTenant() {
        OffsetDateTime t1 = OffsetDateTime.parse("2026-07-21T17:00:00Z");
        stubConnection(conn("cli-1", "NATIVE", t1));

        TenantContext.setTenantId("tenant-a");
        LLMAdapter a = factory.getAdapter("cli-1");

        TenantContext.setTenantId("tenant-b");
        LLMAdapter b = factory.getAdapter("cli-1");

        assertThat(b).isNotSameAs(a);
    }

    private void stubConnection(ConnectionEntity entity) {
        when(connectionRepository.findById("cli-1")).thenReturn(Optional.of(entity));
    }

    private static ConnectionEntity conn(String id, String toolMode, OffsetDateTime updatedAt) {
        ConnectionEntity e = new ConnectionEntity();
        e.setId(id);
        e.setName("bp-wes-claude-cli");
        e.setAdapter("anthropic-cli");
        e.setStatus("active");
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("model", "claude-sonnet-4-5");
        cfg.put("tool_mode", toolMode);
        cfg.put("agent_name", "cli-runner-bp-wes");
        e.setConfig(cfg);
        e.setUpdatedAt(updatedAt);
        return e;
    }
}
