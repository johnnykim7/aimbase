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

/**
 * CR-013: API Rate Limit 필터.
 *
 * TenantResolver(@Order -200) 뒤, SecurityFilterChain 앞에서 실행.
 * 테넌트별 분당 요청 수를 Redis TokenBucket으로 제한.
 * 초과 시 429 Too Many Requests + X-RateLimit-* 헤더 반환.
 */
@Component
@Order(-100)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

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
            || path.startsWith("/api/v1/auth/");
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
