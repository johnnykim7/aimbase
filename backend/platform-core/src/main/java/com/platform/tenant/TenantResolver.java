package com.platform.tenant;

import com.platform.app.AppContext;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servlet Filter — 모든 요청에서 tenant_id와 app_id를 추출하여 Context에 설정.
 *
 * 추출 우선순위:
 *   1. X-Tenant-Id 헤더 (개발/테스트 편의)
 *   2. 서브도메인 (예: acme.platform.com → tenant_id = "acme")
 *   3. JWT tenant_id claim (Phase 5 인증 강화 시 활성화)
 *
 * 경로별 동작:
 *   /api/v1/platform/**              → Master DB만 (TenantContext/AppContext 미설정)
 *   /api/v1/apps/{appId}/**          → AppContext만 설정 (소비앱 어드민 API)
 *   그 외                             → TenantContext 설정 + X-App-Id 헤더로 AppContext 설정
 */
@Component
@Order(-200)
public class TenantResolver implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TenantResolver.class);
    private static final String TENANT_HEADER = "X-Tenant-Id";
    private static final String APP_HEADER = "X-App-Id";
    private static final String PLATFORM_API_PREFIX = "/api/v1/platform";
    private static final Pattern APP_API_PATTERN = Pattern.compile("^/api/v1/apps/([^/]+)(/.*)?$");

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();

        try {
            if (path.startsWith(PLATFORM_API_PREFIX)) {
                // 슈퍼어드민 API → Master DB만 사용
                log.debug("Platform API path, using Master DB: {}", path);

            } else {
                // App API 경로 체크: /api/v1/apps/{appId}/**
                Matcher appMatcher = APP_API_PATTERN.matcher(path);
                if (appMatcher.matches()) {
                    String appId = appMatcher.group(1);
                    AppContext.setAppId(appId);
                    log.debug("App resolved from URL: {} for path: {}", appId, path);
                } else {
                    // 일반 API → TenantContext 설정
                    String tenantId = resolveTenantId(httpRequest);
                    if (tenantId != null && !tenantId.isBlank()) {
                        TenantContext.setTenantId(tenantId);
                        log.debug("Tenant resolved: {} for path: {}", tenantId, path);
                    }

                    // X-App-Id 헤더가 있으면 AppContext도 설정
                    String appId = httpRequest.getHeader(APP_HEADER);
                    if (appId != null && !appId.isBlank()) {
                        AppContext.setAppId(appId.trim());
                    }
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            AppContext.clear();
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
            if (!subdomain.equals("localhost") && !subdomain.equals("www") && !subdomain.equals("api")) {
                return subdomain;
            }
        }

        // 3. JWT tenant_id claim (Phase 5에서 구현)

        return null;
    }
}
