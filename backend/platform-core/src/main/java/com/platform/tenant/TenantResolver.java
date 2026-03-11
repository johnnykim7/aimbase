package com.platform.tenant;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Servlet Filter — 모든 요청에서 tenant_id를 추출하여 TenantContext에 설정.
 *
 * 추출 우선순위:
 *   1. X-Tenant-Id 헤더 (개발/테스트 편의)
 *   2. 서브도메인 (예: acme.platform.com → tenant_id = "acme")
 *   3. JWT tenant_id claim (Phase 5 인증 강화 시 활성화)
 *
 * /api/v1/platform/** 경로는 슈퍼어드민 전용 → Master DB 사용 (TenantContext 설정 안 함)
 */
@Component
@Order(1)
public class TenantResolver implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TenantResolver.class);
    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final String PLATFORM_API_PREFIX = "/api/v1/platform";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        try {
            // 슈퍼어드민 API는 Master DB만 사용 — TenantContext 설정 안 함
            if (!path.startsWith(PLATFORM_API_PREFIX)) {
                String tenantId = resolveTenantId(httpRequest);
                if (tenantId != null && !tenantId.isBlank()) {
                    TenantContext.setTenantId(tenantId);
                    log.debug("Tenant resolved: {} for path: {}", tenantId, path);
                }
            }
            chain.doFilter(request, response);
        } finally {
            // 메모리 누수 방지 — 반드시 clear
            TenantContext.clear();
        }
    }

    private String resolveTenantId(HttpServletRequest request) {
        // 1. X-Tenant-Id 헤더
        String headerTenantId = request.getHeader(TENANT_HEADER);
        if (headerTenantId != null && !headerTenantId.isBlank()) {
            return headerTenantId.trim();
        }

        // 2. 서브도메인 (예: acme.platform.com)
        String host = request.getServerName();
        if (host != null && host.contains(".")) {
            String subdomain = host.split("\\.")[0];
            // 로컬/공통 호스트명 제외
            if (!subdomain.equals("localhost") && !subdomain.equals("www") && !subdomain.equals("api")) {
                return subdomain;
            }
        }

        // 3. JWT tenant_id claim (Phase 5에서 구현)
        // String authHeader = request.getHeader("Authorization");
        // if (authHeader != null && authHeader.startsWith("Bearer ")) { ... }

        return null;
    }
}
