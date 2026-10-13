package com.platform.mcp.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.auth.ApiKeyAuthenticationFilter;
import com.platform.auth.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * CR-150: 관리 도구(MCP)는 인증한 키나 토큰의 테넌트만 다룬다(BIZ-124).
 *
 * <p>연결 주소의 tenant_id 와 세션에 묶인 테넌트는 인증 전에 정해지므로(McpTenantSessionFilter),
 * 인증 주체의 테넌트와 같은지는 인증이 끝난 이 자리에서 대조한다. 통과한 요청에만
 * {@link #ACCEPTED_TENANT_ATTR} 를 남기고, McpTenantSessionFilter 는 이 표시가 있을 때만
 * 세션을 묶거나 푼다. 그래야 남의 세션 아이디를 주운 요청이 세션 테넌트를 바꾸지 못한다.
 *
 * <p>인증이 없으면 그대로 두어 권한 판정(authenticated)이 403 으로 막게 한다.
 * 스프링 빈으로 두면 서블릿 필터로도 한 번 더 등록되므로 SecurityConfig 에서 직접 만든다.
 */
public class AdminMcpTenantGuard extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminMcpTenantGuard.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 검사를 통과한 요청에 남기는 인증 주체의 테넌트. */
    public static final String ACCEPTED_TENANT_ATTR = AdminMcpTenantGuard.class.getName() + ".acceptedTenant";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin-mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }

        String rejection = rejection(request, principal);
        if (rejection != null) {
            log.warn("Admin MCP rejected: {} (principal={}, tenant={}, path={})",
                    rejection, principal.getId(), principal.getTenantId(), request.getRequestURI());
            SecurityContextHolder.clearContext();
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(JSON.writeValueAsString(Map.of("status", "ERROR", "message", rejection)));
            return;
        }

        request.setAttribute(ACCEPTED_TENANT_ATTR, principal.getTenantId());
        chain.doFilter(request, response);
    }

    static String rejection(HttpServletRequest request, UserPrincipal principal) {
        // 테넌트 없는 키는 주소의 테넌트를 자기 테넌트로 받아 들이므로 대조가 늘 통과한다
        if (Boolean.TRUE.equals(request.getAttribute(ApiKeyAuthenticationFilter.TENANTLESS_KEY_ATTR))) {
            return "API key without a tenant cannot use admin MCP";
        }
        String own = principal.getTenantId();
        if (own == null || own.isBlank()) {
            return "admin MCP requires a credential bound to a tenant";
        }
        String requested = request.getParameter("tenant_id");
        if (requested != null && !requested.isBlank() && !requested.trim().equals(own)) {
            return "tenant_id does not match the credential's tenant";
        }
        String sessionTenant = McpTenantSessionFilter.getTenantForSession(
                request.getHeader(McpTenantSessionFilter.SESSION_ID_HEADER));
        if (sessionTenant != null && !sessionTenant.equals(own)) {
            return "MCP session belongs to another tenant";
        }
        return null;
    }
}
