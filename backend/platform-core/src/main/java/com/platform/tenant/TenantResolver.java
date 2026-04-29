package com.platform.tenant;

import com.platform.app.AppContext;
import com.platform.auth.JwtProvider;
import io.jsonwebtoken.Claims;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servlet Filter — 모든 요청에서 tenant_id와 app_id를 추출하여 Context에 설정.
 *
 * 추출 우선순위:
 *   1. X-Tenant-Id 헤더 (개발/테스트 편의)
 *   2. 쿼리 파라미터 tenant_id (MCP SSE 클라이언트용)
 *   3. 위젯 JWT tenant_id 클레임 (CR-058, type=widget 인 경우)
 *   4. 서브도메인 (예: acme.platform.com → tenant_id = "acme")
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
    private static final String AUTH_API_PREFIX = "/api/v1/auth";
    private static final String ADMIN_API_PREFIX = "/api/v1/admin";
    private static final String API_PREFIX = "/api/v1/";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern APP_API_PATTERN = Pattern.compile("^/api/v1/apps/([^/]+)(/.*)?$");

    private final ObjectProvider<JwtProvider> jwtProviderProvider;

    @Autowired
    public TenantResolver(ObjectProvider<JwtProvider> jwtProviderProvider) {
        this.jwtProviderProvider = jwtProviderProvider;
    }

    /** 테스트용 — Spring 빈 주입 흐름이 아닐 때 위젯 폴백 비활성. */
    public TenantResolver() {
        this.jwtProviderProvider = null;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String path = httpRequest.getRequestURI();

        // CR-058: CORS preflight(OPTIONS)는 커스텀 헤더를 가지지 않는다.
        // Tenant 헤더 검사 전에 Spring CORS 처리로 위임한다.
        if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

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
                    } else if (requiresTenant(path)) {
                        // Tenant 필수 경로인데 X-Tenant-Id 누락 → 400
                        log.warn("Missing X-Tenant-Id header for tenant-required path: {}", path);
                        httpResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        httpResponse.getWriter().write(
                                "{\"status\":\"ERROR\",\"message\":\"X-Tenant-Id header is required\"}");
                        return;
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

    /**
     * Tenant ID가 필수인 경로인지 판별.
     * /api/v1/platform/**, /api/v1/admin/**, 비-API 경로는 제외.
     * /api/v1/auth/**는 tenant DB(users 테이블)를 조회하므로 tenant 필수.
     */
    private boolean requiresTenant(String path) {
        if (!path.startsWith(API_PREFIX)) return false;
        if (path.startsWith(PLATFORM_API_PREFIX)) return false;
        if (path.startsWith(ADMIN_API_PREFIX)) return false;
        // CR-027: /auth/login은 이메일로 tenant 자동 resolve → tenant 필수 아님
        if (path.equals("/api/v1/auth/login")) return false;
        return true;
    }

    private String resolveTenantId(HttpServletRequest request) {
        // 1. X-Tenant-Id 헤더
        String headerTenantId = request.getHeader(TENANT_HEADER);
        if (headerTenantId != null && !headerTenantId.isBlank()) {
            return headerTenantId.trim();
        }

        // 2. 쿼리 파라미터 tenant_id (MCP SSE 클라이언트용 — 헤더 전달 불가)
        String queryTenantId = request.getParameter("tenant_id");
        if (queryTenantId != null && !queryTenantId.isBlank()) {
            return queryTenantId.trim();
        }

        // 3. 위젯 JWT tenant_id 클레임 (CR-058)
        // 위젯 패턴은 토큰 하나로 인증+테넌트 식별이 모두 끝나야 한다.
        // type=widget 토큰만 폴백 대상 — access 토큰은 JwtAuthenticationFilter 가 처리.
        String widgetTenantId = resolveTenantIdFromWidgetToken(request);
        if (widgetTenantId != null) {
            return widgetTenantId;
        }

        // /api/v1/auth/login은 이메일로 tenant를 자동 resolve하므로
        // 서브도메인 fallback을 적용하면 안 됨 (예: IP 접속 시 '59.8.160.12' → '59'로 오판)
        if ("/api/v1/auth/login".equals(request.getRequestURI())) {
            return null;
        }

        // 4. 서브도메인 (예: acme.platform.com)
        String host = request.getServerName();
        if (host != null && host.contains(".") && !isIpAddress(host)) {
            String subdomain = host.split("\\.")[0];
            if (!subdomain.equals("localhost") && !subdomain.equals("www") && !subdomain.equals("api")) {
                return subdomain;
            }
        }

        return null;
    }

    /**
     * 위젯 토큰(type=widget)에서 tenant_id 클레임을 추출.
     * Authorization: Bearer {token} 또는 ?access_token= 쿼리에서 토큰을 찾는다.
     * 검증 실패/non-widget 토큰/JwtProvider 미주입 시 null.
     */
    private String resolveTenantIdFromWidgetToken(HttpServletRequest request) {
        if (jwtProviderProvider == null) return null;
        JwtProvider jwtProvider = jwtProviderProvider.getIfAvailable();
        if (jwtProvider == null) return null;

        String token = null;
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            token = authHeader.substring(BEARER_PREFIX.length()).trim();
        } else {
            String queryToken = request.getParameter("access_token");
            if (queryToken != null && !queryToken.isBlank()) {
                token = queryToken.trim();
            }
        }
        if (token == null || token.isEmpty()) return null;
        if (!jwtProvider.validateToken(token)) return null;

        try {
            Claims claims = jwtProvider.extractClaims(token);
            if (!"widget".equals(claims.get("type", String.class))) return null;
            String tid = claims.get("tenant_id", String.class);
            if (tid == null || tid.isBlank()) return null;
            return tid.trim();
        } catch (Exception e) {
            log.debug("Widget token tenant_id extraction failed: {}", e.getMessage());
            return null;
        }
    }

    private static final Pattern IPV4_PATTERN = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private boolean isIpAddress(String host) {
        return IPV4_PATTERN.matcher(host).matches();
    }
}
