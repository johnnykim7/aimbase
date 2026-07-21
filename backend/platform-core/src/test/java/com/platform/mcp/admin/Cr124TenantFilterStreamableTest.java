package com.platform.mcp.admin;

import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * CR-124: Streamable 단일 엔드포인트(POST /admin-mcp)에서도 테넌트가 전파되는지 실제 필터로 검증.
 */
class Cr124TenantFilterStreamableTest {

    private final TenantDataSourceManager dsm = mock(TenantDataSourceManager.class);
    private final McpTenantSessionFilter filter = new McpTenantSessionFilter(dsm);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 필터 체인 안에서 관측된 TenantContext 값을 잡아낸다. */
    private String runAndCapture(MockHttpServletRequest req) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        var chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest rq, jakarta.servlet.ServletResponse rs) {
                seen.set(TenantContext.getTenantId());
            }
        };
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        return seen.get();
    }

    @Test
    void streamableBarePath_withTenantParam_propagatesTenant() throws Exception {
        var req = new MockHttpServletRequest("POST", "/admin-mcp");
        req.setParameter("tenant_id", "axopm_companyA");

        assertThat(runAndCapture(req)).isEqualTo("axopm_companyA");
        // 응답 시작 전 DataSource 준비 보장
        verify(dsm).getTenantDataSource("axopm_companyA");
    }

    @Test
    void streamableBarePath_withoutParam_reusesLastTenant() throws Exception {
        var first = new MockHttpServletRequest("POST", "/admin-mcp");
        first.setParameter("tenant_id", "tenant_x");
        runAndCapture(first);

        // 후속 호출은 tenant_id 없이 온다 (Streamable 은 초기화 후 같은 EP 로 계속 POST)
        var next = new MockHttpServletRequest("POST", "/admin-mcp");
        assertThat(runAndCapture(next)).isEqualTo("tenant_x");
    }

    @Test
    void filterDoesNotLeakTenantAfterRequest() throws Exception {
        var req = new MockHttpServletRequest("POST", "/admin-mcp");
        req.setParameter("tenant_id", "tenant_leak_check");
        runAndCapture(req);

        // 요청 종료 후에는 이 필터가 심은 값이 남지 않아야 한다
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void nonAdminMcpPath_isUntouched() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/v1/workflows");
        assertThat(runAndCapture(req)).isNull();
    }
}
