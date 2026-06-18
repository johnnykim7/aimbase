package com.platform.api.filter;

import com.platform.llm.adapter.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * CR-071 Phase 4: 요청 헤더 X-Aimbase-Agent-Id → ThreadLocal RequestContext 전파.
 *
 * <p>{@link com.platform.llm.adapter.ClaudeCliAdapter} 가 라우팅 키로 사용.
 * 누락 시 즉시 400 에러를 내지 않고, Adapter 호출 시점에 검증한다 — Anthropic/OpenAI 등
 * 다른 어댑터는 헤더 없이도 동작해야 하므로.
 *
 * <p>CR-117: 추가로 X-Aimbase-Workspace-Path 헤더(CLI 워커의 run 격리 cwd)를 전파한다.
 * {@link com.platform.mcp.server.ServerMcpToolDispatcher} 가 file_write 등 도구의
 * ToolContext.workspacePath 로 채워, 내장 도구 cwd 와 동일 작업장을 보게 한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class AgentIdRequestFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Aimbase-Agent-Id";
    /** CR-117: CLI 워커가 보내는 run 격리 작업장 절대경로 헤더. */
    public static final String WORKSPACE_HEADER = "X-Aimbase-Workspace-Path";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String agentId = request.getHeader(HEADER);
        String workspacePath = request.getHeader(WORKSPACE_HEADER);
        try {
            if (agentId != null && !agentId.isBlank()) {
                RequestContext.setAgentId(agentId.trim());
            }
            if (workspacePath != null && !workspacePath.isBlank()) {
                RequestContext.setWorkspacePath(workspacePath.trim());
            }
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }
}
