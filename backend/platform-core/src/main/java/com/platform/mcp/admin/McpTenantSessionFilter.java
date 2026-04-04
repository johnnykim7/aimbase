package com.platform.mcp.admin;

import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * MCP SSE 연결의 tenant_id를 메시지 호출까지 전파하는 필터.
 *
 * SSE 연결: /admin-mcp/sse?tenant_id=xxx → 테넌트 저장 + DataSource 초기화 보장
 * 메시지 호출: /admin-mcp/message?sessionId=xxx → 저장된 테넌트를 TenantContext에 설정
 *
 * DataSource 초기화가 완료된 후에 chain.doFilter()가 호출되므로,
 * WebMvcSseServerTransportProvider가 endpoint 이벤트를 발행하는 시점에는
 * 테넌트 DataSource가 반드시 준비되어 있음.
 */
@Component
@Order(-199)
public class McpTenantSessionFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(McpTenantSessionFilter.class);

    private final TenantDataSourceManager dataSourceManager;

    /** 현재 MCP 세션의 테넌트. SSE 연결 시 설정. */
    private static volatile String currentMcpTenant = null;

    public McpTenantSessionFilter(TenantDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    public static String getCurrentMcpTenant() {
        return currentMcpTenant;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        String path = req.getRequestURI();

        if (path.startsWith("/admin-mcp/sse")) {
            // SSE 연결: tenant_id 저장 + DataSource 초기화 보장
            String tenantId = req.getParameter("tenant_id");
            if (tenantId != null && !tenantId.isBlank()) {
                currentMcpTenant = tenantId.trim();
                // endpoint 이벤트 발행 전에 DataSource가 준비되어야 함
                dataSourceManager.getTenantDataSource(currentMcpTenant);
                log.info("MCP SSE connected: tenant '{}', DataSource ensured", currentMcpTenant);
            }
        } else if (path.startsWith("/admin-mcp/message")) {
            // 메시지 호출: 저장된 테넌트를 TenantContext에 설정
            if (currentMcpTenant != null && TenantContext.getTenantId() == null) {
                TenantContext.setTenantId(currentMcpTenant);
                log.debug("MCP message: set tenant '{}'", currentMcpTenant);
            }
        }

        chain.doFilter(request, response);
    }
}
