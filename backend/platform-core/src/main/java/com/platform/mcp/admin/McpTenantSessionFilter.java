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
 * MCP 연결의 tenant_id를 도구 호출까지 전파하는 필터.
 *
 * <p>CR-124 (Streamable HTTP 전환): 요청 경로 형태가 바뀌었다.
 * <pre>
 *   기존(SSE)        GET  /admin-mcp/sse?tenant_id=X   → 이후 POST /admin-mcp/message
 *   전환후(Streamable) POST /admin-mcp?tenant_id=X       (하위경로 없는 단일 엔드포인트)
 * </pre>
 * 기존 구현은 {@code /admin-mcp/sse} · {@code /admin-mcp/message} 두 하위경로 문자열에만
 * 반응했기 때문에 그대로 두면 Streamable 요청을 하나도 잡지 못해 테넌트 전파가 끊긴다.
 * 이제 {@code /admin-mcp} 이하 전체를 대상으로 하고, {@code tenant_id} 파라미터가 있으면
 * 저장하고 없으면 마지막으로 저장된 값을 사용한다.</p>
 *
 * <p>DataSource 초기화가 완료된 후에 chain.doFilter()가 호출되므로,
 * transport 가 응답을 시작하는 시점에는 테넌트 DataSource가 반드시 준비되어 있다.</p>
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

        // CR-124: SSE 하위경로(/sse, /message)와 Streamable 단일 엔드포인트(/admin-mcp)를 모두 커버.
        if (!path.startsWith("/admin-mcp")) {
            chain.doFilter(request, response);
            return;
        }

        boolean tenantSet = false;
        try {
            String tenantId = req.getParameter("tenant_id");
            if (tenantId != null && !tenantId.isBlank()) {
                // 연결/호출 시점에 tenant_id 가 실려오면 그것을 신뢰하고 갱신한다.
                currentMcpTenant = tenantId.trim();
                // 응답 시작 전에 DataSource 가 준비되어야 함
                dataSourceManager.getTenantDataSource(currentMcpTenant);
                log.info("MCP connected: tenant '{}', DataSource ensured (path={})", currentMcpTenant, path);
            }
            if (currentMcpTenant != null && TenantContext.getTenantId() == null) {
                TenantContext.setTenantId(currentMcpTenant);
                tenantSet = true;
                log.debug("MCP request: set tenant '{}' (path={})", currentMcpTenant, path);
            }

            chain.doFilter(request, response);
        } finally {
            // CR-124: Streamable 은 요청마다 스레드가 재사용될 수 있어, 이 필터가 심은 값은
            // 같은 요청 범위에서 되돌린다(누수 방지). 원래 값이 있던 경우는 건드리지 않는다.
            if (tenantSet) {
                TenantContext.clear();
            }
        }
    }
}
