package com.platform.llm.adapter;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * CR-071 Phase 4: 요청 헤더 X-Aimbase-Agent-Id 를 ThreadLocal 로 전파.
 *
 * <p>Servlet Filter ({@link AgentIdRequestFilter}) 가 request scope 에서 set,
 * ClaudeCliAdapter 가 사용. VT 전파는 호출자(ChatController 등) 가
 * capture/restore 책임을 진다 — TenantContext 패턴과 동일.
 */
public final class RequestContext {

    private static final ThreadLocal<String> AGENT_ID = new ThreadLocal<>();

    private RequestContext() {}

    public static void setAgentId(String id) { AGENT_ID.set(id); }

    public static String getAgentId() { return AGENT_ID.get(); }

    /**
     * agent-id 없으면 400. ClaudeCliAdapter 가 호출.
     */
    public static String requireAgentId() {
        String id = AGENT_ID.get();
        if (id == null || id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "X-Aimbase-Agent-Id header required for ClaudeCliAdapter");
        }
        return id;
    }

    public static void clear() { AGENT_ID.remove(); }
}
