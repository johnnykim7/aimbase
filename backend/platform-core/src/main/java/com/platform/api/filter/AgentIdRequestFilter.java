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
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class AgentIdRequestFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Aimbase-Agent-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String agentId = request.getHeader(HEADER);
        try {
            if (agentId != null && !agentId.isBlank()) {
                RequestContext.setAgentId(agentId.trim());
            }
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }
}
