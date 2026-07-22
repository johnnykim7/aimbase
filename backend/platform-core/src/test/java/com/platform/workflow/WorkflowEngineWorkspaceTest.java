package com.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.WorkspaceProperties;
import com.platform.monitoring.PlatformMetrics;
import com.platform.repository.PendingApprovalRepository;
import com.platform.repository.WorkflowRepository;
import com.platform.repository.WorkflowRunRepository;
import com.platform.session.SessionStore;
import com.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-107 후속: 워크플로우 run 의 격리 workspace 경로 결정 검증.
 * 모든 run 이 {tenant}/general 한 디렉토리에 누적되던 버그(run 별 격리 부재) 회귀 방지.
 */
class WorkflowEngineWorkspaceTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private WorkflowEngine engineWith(SessionStore sessionStore, WorkspaceProperties props) {
        ObjectProvider<SessionStore> ssProvider = mock(ObjectProvider.class);
        when(ssProvider.getIfAvailable()).thenReturn(sessionStore);
        ObjectProvider<WorkspaceProperties> wpProvider = mock(ObjectProvider.class);
        when(wpProvider.getIfAvailable()).thenReturn(props);

        return new WorkflowEngine(
                mock(WorkflowRepository.class),
                mock(WorkflowRunRepository.class),
                mock(PendingApprovalRepository.class),
                new ObjectMapper(),
                List.of(),
                mock(PlatformMetrics.class),
                mock(com.platform.workflow.event.WorkflowEventPublisher.class),
                null,
                ssProvider,
                wpProvider,
                null,   // CR-116: activeCliWorkerRegistryProvider
                null,   // CR-116: connectionAdapterFactoryProvider
                null);  // CR-121: cancelRegistryProvider (엔진 자체 폴백 인스턴스)
    }

    private WorkspaceProperties propsWithBase(String base) {
        WorkspaceProperties p = new WorkspaceProperties();
        p.setBase(base);
        return p;
    }

    @Test
    void noSessionRef_buildsRunScopedPath() {
        TenantContext.setTenantId("bidding_system");
        SessionStore ss = mock(SessionStore.class);
        when(ss.getWorkspaceRef(any())).thenReturn(null); // 워크플로우 run 세션엔 ref 없음

        WorkflowEngine engine = engineWith(ss, propsWithBase("/data/workspace"));
        String path = engine.resolveWorkspacePath("run-abc", "workflow-run-xyz");

        // run 단위 격리 — tenant/general 폴백 아님
        assertThat(path).isEqualTo("/data/workspace/bidding_system/runs/run-abc");
    }

    @Test
    void explicitSessionRef_takesPriority() {
        SessionStore ss = mock(SessionStore.class);
        when(ss.getWorkspaceRef("sess-1")).thenReturn("/custom/ws");

        WorkflowEngine engine = engineWith(ss, propsWithBase("/data/workspace"));
        String path = engine.resolveWorkspacePath("run-abc", "sess-1");

        assertThat(path).isEqualTo("/custom/ws"); // 채팅 연동 워크플로우 보존
    }

    @Test
    void noTenant_fallsBackToDefaultTenantInPath() {
        SessionStore ss = mock(SessionStore.class);
        when(ss.getWorkspaceRef(any())).thenReturn(null);

        WorkflowEngine engine = engineWith(ss, propsWithBase("/data/workspace"));
        String path = engine.resolveWorkspacePath("run-9", null);

        assertThat(path).isEqualTo("/data/workspace/default/runs/run-9");
    }

    @Test
    void noWorkspaceProperties_returnsNull_legacyFallback() {
        SessionStore ss = mock(SessionStore.class);
        when(ss.getWorkspaceRef(any())).thenReturn(null);

        WorkflowEngine engine = engineWith(ss, null); // base 미가용
        String path = engine.resolveWorkspacePath("run-9", "sess");

        assertThat(path).isNull(); // 하위호환 — WorkspaceResolver 가 기존 폴백
    }
}
