package com.platform.mcp.admin;

import com.platform.auth.ApiKeyAuthenticationFilter;
import com.platform.auth.UserPrincipal;
import com.platform.tenant.TenantDataSourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * CR-150 TC-MCP-ADM-010~015: 관리 도구(MCP)는 인증한 키나 토큰의 테넌트만 다룬다(BIZ-124).
 */
class AdminMcpTenantGuardTest {

    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final AdminMcpTenantGuard guard = new AdminMcpTenantGuard();

    @BeforeEach
    void setUp() {
        McpTenantSessionFilter.clearAllSessions();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        McpTenantSessionFilter.clearAllSessions();
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String principalId, String tenantId) {
        var principal = new UserPrincipal(principalId, "x@test", tenantId, "admin", Map.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private MockHttpServletRequest adminMcp(String tenantParam) {
        var req = new MockHttpServletRequest("POST", "/admin-mcp");
        if (tenantParam != null) {
            req.setParameter("tenant_id", tenantParam);
        }
        return req;
    }

    /** 체인까지 갔으면 true. */
    private boolean run(MockHttpServletRequest req, MockHttpServletResponse res) throws Exception {
        var chain = new MockFilterChain();
        guard.doFilter(req, res, chain);
        return chain.getRequest() != null;
    }

    /** 세션을 tenant 에 묶어 둔다(통과한 initialize 를 흉내). */
    private void bind(String sessionId, String tenant) throws Exception {
        var sessionFilter = new McpTenantSessionFilter(mock(TenantDataSourceManager.class));
        var chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest rq, jakarta.servlet.ServletResponse rs) {
                ((MockHttpServletResponse) rs).setHeader(SESSION_HEADER, sessionId);
                rq.setAttribute(AdminMcpTenantGuard.ACCEPTED_TENANT_ATTR, tenant);
            }
        };
        sessionFilter.doFilter(adminMcp(tenant), new MockHttpServletResponse(), chain);
    }

    // TC-MCP-ADM-010: 인증 없음은 권한 판정(authenticated)이 막으므로 여기서는 표시만 남기지 않는다
    @Test
    void unauthenticated_passesWithoutAcceptance() throws Exception {
        var req = adminMcp("tenant_a");

        assertThat(run(req, new MockHttpServletResponse())).isTrue();
        assertThat(req.getAttribute(AdminMcpTenantGuard.ACCEPTED_TENANT_ATTR)).isNull();
    }

    // TC-MCP-ADM-011
    @Test
    void keyOfSameTenant_isAccepted() throws Exception {
        authenticate("system-key-a", "tenant_a");
        var req = adminMcp("tenant_a");

        assertThat(run(req, new MockHttpServletResponse())).isTrue();
        assertThat(req.getAttribute(AdminMcpTenantGuard.ACCEPTED_TENANT_ATTR)).isEqualTo("tenant_a");
    }

    // TC-MCP-ADM-012
    @Test
    void tenantParamOfAnotherTenant_isForbidden() throws Exception {
        authenticate("system-key-a", "tenant_a");
        var req = adminMcp("tenant_b");
        var res = new MockHttpServletResponse();

        assertThat(run(req, res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("tenant_id does not match");
        assertThat(req.getAttribute(AdminMcpTenantGuard.ACCEPTED_TENANT_ATTR)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // TC-MCP-ADM-013
    @Test
    void sessionOfAnotherTenant_isForbidden() throws Exception {
        bind("sess-B", "tenant_b");
        authenticate("system-key-a", "tenant_a");
        var req = adminMcp(null);
        req.addHeader(SESSION_HEADER, "sess-B");
        var res = new MockHttpServletResponse();

        assertThat(run(req, res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("another tenant");
    }

    // TC-MCP-ADM-014
    @Test
    void keyWithoutTenant_isForbidden() throws Exception {
        authenticate("system-domain-key", "tenant_a");
        var req = adminMcp("tenant_a");
        req.setAttribute(ApiKeyAuthenticationFilter.TENANTLESS_KEY_ATTR, Boolean.TRUE);
        var res = new MockHttpServletResponse();

        assertThat(run(req, res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("without a tenant");
    }

    // TC-MCP-ADM-015
    @Test
    void loginTokenOfSameTenant_isAccepted_andOtherTenantForbidden() throws Exception {
        authenticate("user-1", "tenant_a");
        assertThat(run(adminMcp("tenant_a"), new MockHttpServletResponse())).isTrue();

        var res = new MockHttpServletResponse();
        assertThat(run(adminMcp("tenant_b"), res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void credentialWithoutTenant_isForbidden() throws Exception {
        authenticate("super-admin", null);
        var res = new MockHttpServletResponse();

        assertThat(run(adminMcp("tenant_a"), res)).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void otherPaths_areUntouched() throws Exception {
        authenticate("system-key-a", "tenant_a");
        var req = new MockHttpServletRequest("POST", "/mcp");
        req.setParameter("tenant_id", "tenant_b");

        assertThat(run(req, new MockHttpServletResponse())).isTrue();
        assertThat(req.getAttribute(AdminMcpTenantGuard.ACCEPTED_TENANT_ATTR)).isNull();
    }
}
