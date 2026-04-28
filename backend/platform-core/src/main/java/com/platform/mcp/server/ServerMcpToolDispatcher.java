package com.platform.mcp.server;

import com.platform.hook.HookDecision;
import com.platform.hook.HookDispatcher;
import com.platform.hook.HookEvent;
import com.platform.hook.HookInput;
import com.platform.hook.HookOutput;
import com.platform.llm.adapter.RequestContext;
import com.platform.policy.TokenBucketRateLimiter;
import com.platform.tool.McpResultTruncator;
import com.platform.tenant.TenantContext;
import com.platform.tool.ToolExecutor;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * CR-072: 서버 MCP endpoint 의 도구 호출 디스패처.
 *
 * <p>{@link com.platform.tool.ToolCallHandler} 가 OrchestratorEngine 경로에서 적용하는 거버넌스
 * (Hook PRE/POST_TOOL_USE) 와 동일 패턴을 MCP 진입 경로에도 적용한다. CR-050 트레이드오프 계승:
 * max_iterations / Plan Mode / SESSION_END 등 풀세트 Hook 은 적용 불가.</p>
 *
 * <p>요청 컨텍스트:
 * <ul>
 *   <li>{@link TenantContext#getTenantId()} — ApiKeyAuthenticationFilter 가 채워둠</li>
 *   <li>{@link RequestContext#getAgentIdOrNull()} — AgentIdRequestFilter 가 채워둠 (선택)</li>
 *   <li>sessionId 는 헤더로 전달 안 됨 → "mcp:" prefix + agent-id 로 합성하거나 null 처리</li>
 * </ul>
 * </p>
 */
@Component
public class ServerMcpToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ServerMcpToolDispatcher.class);

    private final HookDispatcher hookDispatcher;
    private final TokenBucketRateLimiter rateLimiter;
    private final int rateLimitPerMinute;

    public ServerMcpToolDispatcher(HookDispatcher hookDispatcher,
                                    TokenBucketRateLimiter rateLimiter,
                                    @Value("${mcp.rate-limit.requests-per-minute:60}")
                                    int rateLimitPerMinute) {
        this.hookDispatcher = hookDispatcher;
        this.rateLimiter = rateLimiter;
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    /**
     * MCP tools/call 진입점.
     *
     * <p>흐름:
     * <ol>
     *   <li>도구 노출 레벨 재검증 (이중 방어)</li>
     *   <li>PRE_TOOL_USE Hook — BLOCK 시 isError</li>
     *   <li>도구 실행 ({@link McpToolConversion} 의 dispatch 와 동일하지만 Hook 인지)</li>
     *   <li>POST_TOOL_USE / POST_TOOL_USE_FAILURE Hook</li>
     *   <li>결과 truncate</li>
     * </ol>
     * </p>
     */
    public McpSchema.CallToolResult dispatch(ToolExecutor tool, String name, Map<String, Object> args) {
        if (!McpExposurePolicy.isCliExposed(tool)) {
            log.warn("Server MCP: blocked non-CLI-exposed tool '{}'", name);
            return errorResult("Tool not exposed via MCP CLI channel");
        }

        // Rate Limit (테넌트 단위, MCP 채널 한정 키 스코프). 익명 호출은 'anon' 으로 묶임.
        String tenantKey = "mcp:" + nonNullOrAnon(TenantContext.getTenantId());
        TokenBucketRateLimiter.RateLimitResult rl = rateLimiter.tryAcquire(tenantKey, rateLimitPerMinute);
        if (!rl.allowed()) {
            log.warn("Server MCP: rate limit exceeded for tool '{}' tenant={} ({}/{}, retry={}s)",
                    name, tenantKey, rl.current(), rl.limit(), rl.retryAfterSeconds());
            return errorResult("Rate limit exceeded; retry after " + rl.retryAfterSeconds() + "s");
        }

        String sessionId = resolveSessionId();
        Map<String, Object> safeArgs = args == null ? Map.of() : args;

        HookOutput preHook = hookDispatcher.dispatch(
                HookEvent.PRE_TOOL_USE,
                HookInput.of(HookEvent.PRE_TOOL_USE, sessionId, name, safeArgs),
                name);
        if (preHook != null && preHook.decision() == HookDecision.BLOCK) {
            log.info("Server MCP: tool '{}' BLOCKED by PRE_TOOL_USE hook (session={})", name, sessionId);
            return errorResult("Tool execution blocked by policy hook");
        }

        String result;
        try {
            result = tool.execute(safeArgs);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            hookDispatcher.dispatch(
                    HookEvent.POST_TOOL_USE_FAILURE,
                    HookInput.of(HookEvent.POST_TOOL_USE_FAILURE, sessionId, name,
                            failureContext(msg)),
                    name);
            log.warn("Server MCP: tool '{}' execution failed: {}", name, msg);
            return errorResult(msg);
        }

        hookDispatcher.dispatch(
                HookEvent.POST_TOOL_USE,
                HookInput.of(HookEvent.POST_TOOL_USE, sessionId, name,
                        successContext(result)),
                name);

        String truncated = McpResultTruncator.truncate(name, result);
        return new McpSchema.CallToolResult(truncated, false);
    }

    private String resolveSessionId() {
        String agentId = RequestContext.getAgentId();
        if (agentId != null && !agentId.isBlank()) {
            return "mcp:" + agentId;
        }
        String tenantId = TenantContext.getTenantId();
        return tenantId != null ? "mcp:" + tenantId : "mcp:anon";
    }

    private static String nonNullOrAnon(String s) {
        return (s == null || s.isBlank()) ? "anon" : s;
    }

    private static McpSchema.CallToolResult errorResult(String message) {
        String safe = message == null ? "" : message.replace("\"", "\\\"");
        return new McpSchema.CallToolResult("{\"error\":\"" + safe + "\"}", true);
    }

    private static Map<String, Object> failureContext(String error) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("error", error == null ? "unknown" : error);
        return ctx;
    }

    private static Map<String, Object> successContext(String result) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("result", result == null ? "" : truncatePreview(result, 500));
        return ctx;
    }

    private static String truncatePreview(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
