package com.platform.tenant;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * CR-021: X-Project-Id 헤더에서 프로젝트 ID를 추출하여 ProjectContext에 설정.
 * TenantResolver 이후에 실행 (@Order(-199)).
 * 선택적 — 헤더 없으면 프로젝트 스코핑 없이 회사 전체 접근.
 */
@Component
@Order(-199)
public class ProjectResolver implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ProjectResolver.class);
    private static final String PROJECT_HEADER = "X-Project-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;

        try {
            String projectId = httpRequest.getHeader(PROJECT_HEADER);
            if (projectId != null && !projectId.isBlank()) {
                ProjectContext.setProjectId(projectId.trim());
                log.debug("Project resolved: {}", projectId);
            }
            chain.doFilter(request, response);
        } finally {
            ProjectContext.clear();
        }
    }
}
