package com.platform.mcp.admin;

import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * CR-150: 인증이나 테넌트 대조에 걸린 요청은 MCP 세션을 묶거나 풀지 못한다.
 * 남의 세션 아이디를 주운 요청이 세션 테넌트를 바꾸거나 지우면 주인의 도구 호출이 다른 테넌트로 가거나 끊긴다.
 */
class Cr150SessionBindingTest {

    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final McpTenantSessionFilter filter = new McpTenantSessionFilter(mock(TenantDataSourceManager.class));

    @BeforeEach
    void setUp() {
        McpTenantSessionFilter.clearAllSessions();
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        McpTenantSessionFilter.clearAllSessions();
        TenantContext.clear();
    }

    /** acceptedTenant 가 null 이면 검사에 걸린 요청이다. */
    private void run(MockHttpServletRequest req, MockHttpServletResponse res, String acceptedTenant, String issuedSession)
            throws Exception {
        var chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest rq, jakarta.servlet.ServletResponse rs) {
                if (issuedSession != null) {
                    ((MockHttpServletResponse) rs).setHeader(SESSION_HEADER, issuedSession);
                }
                if (acceptedTenant != null) {
                    rq.setAttribute(AdminMcpTenantGuard.ACCEPTED_TENANT_ATTR, acceptedTenant);
                }
            }
        };
        filter.doFilter(req, res, chain);
    }

    private MockHttpServletRequest initialize(String tenantParam) {
        var req = new MockHttpServletRequest("POST", "/admin-mcp");
        if (tenantParam != null) {
            req.setParameter("tenant_id", tenantParam);
        }
        return req;
    }

    @Test
    void rejectedInitialize_bindsNothing() throws Exception {
        run(initialize("tenant_b"), new MockHttpServletResponse(), null, "sess-X");

        assertThat(McpTenantSessionFilter.getTenantForSession("sess-X")).isNull();
    }

    @Test
    void acceptedInitialize_bindsCredentialTenant() throws Exception {
        run(initialize(null), new MockHttpServletResponse(), "tenant_a", "sess-A");

        assertThat(McpTenantSessionFilter.getTenantForSession("sess-A")).isEqualTo("tenant_a");
    }

    @Test
    void rejectedRequest_cannotRebindExistingSession() throws Exception {
        run(initialize("tenant_a"), new MockHttpServletResponse(), "tenant_a", "sess-A");

        var hijack = new MockHttpServletRequest("POST", "/admin-mcp");
        hijack.addHeader(SESSION_HEADER, "sess-A");
        hijack.setParameter("tenant_id", "tenant_b");
        run(hijack, new MockHttpServletResponse(), null, null);

        assertThat(McpTenantSessionFilter.getTenantForSession("sess-A")).isEqualTo("tenant_a");
    }

    @Test
    void rejectedDelete_keepsSession() throws Exception {
        run(initialize("tenant_a"), new MockHttpServletResponse(), "tenant_a", "sess-A");

        var del = new MockHttpServletRequest("DELETE", "/admin-mcp");
        del.addHeader(SESSION_HEADER, "sess-A");
        run(del, new MockHttpServletResponse(), null, null);

        assertThat(McpTenantSessionFilter.getTenantForSession("sess-A")).isEqualTo("tenant_a");
    }
}
