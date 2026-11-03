package com.platform.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.domain.master.SubscriptionEntity;
import com.platform.repository.master.SubscriptionRepository;
import com.platform.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CR-013: API Rate Limit 필터.
 *
 * TenantResolver(@Order -200) 뒤에서 실행한다. 순서값이 같은 Spring Security 필터 묶음이 먼저 등록되어
 * 인증을 통과한 요청만 여기에 온다(CR-168 로컬 실측).
 * 테넌트별 분당 요청 수를 Redis TokenBucket으로 제한.
 * 초과 시 429 Too Many Requests + X-RateLimit-* 헤더 반환.
 */
@Component
@Order(-100)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    // CR-168 BIZ-141: 실행기 연결 신호(등록, 상태 알림). 업무 요청이 한도를 다 써도 상태 알림이 거절되어
    // 실행기가 끊김(STALE)으로 바뀌지 않게 한도에서 제외한다. 실행기와 AgentRegistryController 가 정하는
    // 경로라 설정값이 아니라 여기 둔다. 상태 알림의 id 는 등록 id(UUID) 모양만 맞춘다.
    private static final String AGENT_REGISTER_PATH = "/api/v1/agents/register";
    private static final Pattern AGENT_HEARTBEAT_PATH = Pattern.compile(
            "^/api/v1/agents/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/heartbeat$");

    private final TokenBucketRateLimiter rateLimiter;
    private final SubscriptionRepository subscriptionRepository;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(TokenBucketRateLimiter rateLimiter,
                           SubscriptionRepository subscriptionRepository,
                           ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.subscriptionRepository = subscriptionRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Rate Limit 대상: /api/v1/** 경로만
        // 인증, 액추에이터, 정적 리소스, MCP 등 제외
        return !path.startsWith("/api/v1/")
            || path.startsWith("/api/v1/auth/")
            || isAgentConnectionSignal(request);
    }

    static boolean isAgentConnectionSignal(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        return AGENT_REGISTER_PATH.equals(path) || AGENT_HEARTBEAT_PATH.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            // 테넌트 미식별 — 플랫폼 API 등, Rate Limit 건너뜀
            filterChain.doFilter(request, response);
            return;
        }

        int rpmLimit = resolveRpmLimit(tenantId);

        TokenBucketRateLimiter.RateLimitResult result = rateLimiter.tryAcquire(tenantId, rpmLimit);

        // Rate Limit 헤더 항상 설정
        response.setIntHeader("X-RateLimit-Limit", result.limit());
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));

        if (!result.allowed()) {
            response.setIntHeader("Retry-After", result.retryAfterSeconds());
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");

            Map<String, Object> body = Map.of(
                "success", false,
                "error", "Rate limit exceeded. Max " + result.limit() + " requests per minute.",
                "data", Map.of(
                    "limit", result.limit(),
                    "current", result.current(),
                    "retry_after_seconds", result.retryAfterSeconds()
                )
            );
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private int resolveRpmLimit(String tenantId) {
        try {
            return subscriptionRepository.findByTenantId(tenantId)
                .map(SubscriptionEntity::getApiRpmLimit)
                .orElse(60); // 기본: free 플랜 한도
        } catch (Exception e) {
            log.warn("Failed to resolve RPM limit for tenant: {}, using default", tenantId, e);
            return 60;
        }
    }
}
