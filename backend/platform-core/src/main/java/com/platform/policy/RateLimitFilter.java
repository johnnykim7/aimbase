package com.platform.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.config.PlatformSettingsService;
import com.platform.auth.UserPrincipal;
import com.platform.domain.master.SubscriptionEntity;
import com.platform.session.ChatOwner;
import com.platform.repository.master.SubscriptionRepository;
import com.platform.tenant.TenantContext;
import com.platform.tenant.plan.SubscriptionPlanService;
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
 * CR-205 BIZ-297: 위젯 토큰으로 인증된 요청(채팅)은 테넌트 칸과 별개의 채팅 칸으로 센다. 채팅이 몰려도
 * 소비앱 서버가 연동키로 부르는 요청(서면 초안 등)이 거절되지 않게 하려는 것이다.
 */
@Component
@Order(-100)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    // CR-168 BIZ-141: 실행기 연결 신호(등록, 상태 알림). 업무 요청이 한도를 다 써도 상태 알림이 거절되어
    // 실행기가 끊김(STALE)으로 바뀌지 않게 테넌트 한도에서 제외하고, 끝없이 들어오지 않게 전용 칸으로 따로 센다.
    // 경로는 실행기와 AgentRegistryController 가 정하므로 설정값이 아니라 여기 둔다. 상태 알림의 id 는 등록 id(UUID) 모양만 맞춘다.
    private static final String AGENT_REGISTER_PATH = "/api/v1/agents/register";
    private static final Pattern AGENT_HEARTBEAT_PATH = Pattern.compile(
            "^/api/v1/agents/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/heartbeat$");
    static final String AGENT_SIGNAL_BUCKET = "agent-signal";
    static final String AGENT_SIGNAL_LIMIT_KEY = "ratelimit.agent-signal-max-per-minute";
    // 설정 행이 아직 없는 서버(마이그레이션 전)에서 쓰는 값. V73 시드와 같다.
    private static final int DEFAULT_AGENT_SIGNAL_LIMIT = 60;
    // CR-191: 설정 행이 아직 없는 서버(마이그레이션 전)와 구독 조회가 실패했을 때만 쓰는 값. V94 의 free 시드와 같다.
    private static final int DEFAULT_RPM_LIMIT = 60;
    // CR-205: 채팅 칸. 키 rl:chat:{tenant}:{minute}. V96 의 free 시드도 60 이라 기본값을 같이 쓴다.
    static final String CHAT_BUCKET = "chat";
    static final String SCOPE_HEADER = "X-RateLimit-Scope";
    static final String SCOPE_TENANT = "tenant";
    static final String SCOPE_CHAT = "chat";
    static final String CHAT_RATE_LIMITED = "chat_rate_limited";

    private final TokenBucketRateLimiter rateLimiter;
    private final SubscriptionRepository subscriptionRepository;
    private final PlatformSettingsService platformSettings;
    private final SubscriptionPlanService planService;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(TokenBucketRateLimiter rateLimiter,
                           SubscriptionRepository subscriptionRepository,
                           PlatformSettingsService platformSettings,
                           SubscriptionPlanService planService,
                           ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.subscriptionRepository = subscriptionRepository;
        this.platformSettings = platformSettings;
        this.planService = planService;
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

        if (isAgentConnectionSignal(request)) {
            // X-RateLimit-* 는 테넌트 한도를 알리는 헤더라 연결 신호에는 싣지 않는다.
            int signalLimit = platformSettings.getInt(AGENT_SIGNAL_LIMIT_KEY, DEFAULT_AGENT_SIGNAL_LIMIT);
            TokenBucketRateLimiter.RateLimitResult signal =
                rateLimiter.tryAcquire(AGENT_SIGNAL_BUCKET, tenantId, signalLimit);
            if (!signal.allowed()) {
                reject(response, signal, "Agent connection signal limit exceeded. Max " + signal.limit() + " per minute.");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        boolean chat = isChatRequest();
        TokenBucketRateLimiter.RateLimitResult result = chat
            ? rateLimiter.tryAcquire(CHAT_BUCKET, tenantId, resolveChatRpmLimit(tenantId))
            : rateLimiter.tryAcquire(tenantId, resolveRpmLimit(tenantId));

        // Rate Limit 헤더 항상 설정
        response.setIntHeader("X-RateLimit-Limit", result.limit());
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        response.setHeader(SCOPE_HEADER, chat ? SCOPE_CHAT : SCOPE_TENANT);

        if (!result.allowed()) {
            if (chat) {
                // 위젯이 코드로 받아 자기 말로 바꾼다. 테넌트 칸의 문장은 소비앱 서버가 읽던 그대로 둔다.
                reject(response, result, CHAT_RATE_LIMITED, SCOPE_CHAT);
            } else {
                reject(response, result, "Rate limit exceeded. Max " + result.limit() + " requests per minute.",
                    SCOPE_TENANT);
            }
            return;
        }

        filterChain.doFilter(request, response);
    }

    /** 위젯 토큰으로 인증된 요청이면 채팅 칸이다(BIZ-297). 인증 정보가 없는 요청(연동키)은 테넌트 칸. */
    static boolean isChatRequest() {
        UserPrincipal principal = ChatOwner.currentPrincipal();
        return ChatOwner.isWidget(principal);
    }

    private void reject(HttpServletResponse response, TokenBucketRateLimiter.RateLimitResult result,
                        String message) throws IOException {
        reject(response, result, message, null);
    }

    private void reject(HttpServletResponse response, TokenBucketRateLimiter.RateLimitResult result,
                        String message, String scope) throws IOException {
        response.setIntHeader("Retry-After", result.retryAfterSeconds());
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("limit", result.limit());
        data.put("current", result.current());
        data.put("retry_after_seconds", result.retryAfterSeconds());
        if (scope != null) data.put("scope", scope);
        Map<String, Object> body = Map.of(
            "success", false,
            "error", message,
            "data", data
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private int resolveRpmLimit(String tenantId) {
        try {
            return subscriptionRepository.findByTenantId(tenantId)
                .map(SubscriptionEntity::getApiRpmLimit)
                .orElseGet(() -> planService.defaultPlanRpmLimit(DEFAULT_RPM_LIMIT));
        } catch (Exception e) {
            log.warn("Failed to resolve RPM limit for tenant: {}, using default", tenantId, e);
            return DEFAULT_RPM_LIMIT;
        }
    }

    private int resolveChatRpmLimit(String tenantId) {
        try {
            return subscriptionRepository.findByTenantId(tenantId)
                .map(SubscriptionEntity::getChatRpmLimit)
                .orElseGet(() -> planService.defaultPlanChatRpmLimit(DEFAULT_RPM_LIMIT));
        } catch (Exception e) {
            log.warn("Failed to resolve chat RPM limit for tenant: {}, using default", tenantId, e);
            return DEFAULT_RPM_LIMIT;
        }
    }
}
