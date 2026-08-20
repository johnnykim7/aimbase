package com.platform.mcp.admin;

import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 연결의 tenant_id를 도구 호출까지 전파하는 필터.
 *
 * <p>CR-124 (Streamable HTTP 전환): 요청 경로 형태가 바뀌었다.
 * <pre>
 *   기존(SSE)        GET  /admin-mcp/sse?tenant_id=X   → 이후 POST /admin-mcp/message
 *   전환후(Streamable) POST /admin-mcp?tenant_id=X       (하위경로 없는 단일 엔드포인트)
 * </pre>
 *
 * <p>CR-125 (테넌트 교차 오염 해소): 기존 구현은 테넌트를 {@code static volatile} 단일 필드에
 * 담아 두고, {@code tenant_id} 파라미터가 없는 후속 요청은 "마지막으로 저장된 값"을 사용했다.
 * 이 때문에 테넌트 A·B가 동시에 접속하면 나중에 연결한 쪽이 전역값을 덮어써서,
 * A의 후속 도구 호출이 B의 DataSource로 라우팅됐다(BIZ-003 Database-per-Tenant 격리 위반).
 *
 * <p>이제 테넌트를 <b>MCP 세션 단위</b>로 격납한다. Streamable HTTP는 세션을
 * {@code Mcp-Session-Id} 헤더로 식별하며, SDK(mcp-core 2.0.0)의 발급 시점은 다음과 같다.
 * <pre>
 *   initialize 요청 : 요청에 세션 ID 없음 → 서블릿이 세션 생성 후 <b>응답 헤더</b>로 내려줌
 *   그 외 모든 요청 : <b>요청 헤더</b>에 세션 ID 필수 (없으면 SDK가 400으로 거부)
 * </pre>
 * 따라서 initialize 구간은 {@code chain.doFilter()} <b>이후</b> 응답 헤더에서 세션 ID를 읽어
 * 그때 매핑을 심고, 후속 요청은 요청 헤더의 세션 ID로 자기 테넌트를 찾는다.
 * 어느 경우에도 다른 세션의 값을 보지 않는다.</p>
 *
 * <p>DataSource 초기화가 완료된 후에 chain.doFilter()가 호출되므로,
 * transport 가 응답을 시작하는 시점에는 테넌트 DataSource가 반드시 준비되어 있다.</p>
 */
@Component
@Order(-199)
public class McpTenantSessionFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(McpTenantSessionFilter.class);

    /** Streamable HTTP 세션 식별 헤더 (SDK mcp-core 2.0.0 규약). */
    static final String SESSION_ID_HEADER = "Mcp-Session-Id";

    /** 유휴 세션 매핑 보관 기간. 이 시간 넘게 안 쓰이면 정리한다. */
    private static final Duration SESSION_TTL = Duration.ofHours(2);

    /** 정리 작업이 매 요청마다 돌지 않도록 하는 최소 간격. */
    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(5);

    private final TenantDataSourceManager dataSourceManager;

    /** MCP 세션 ID → 테넌트. CR-125: 전역 단일 필드를 대체한다. */
    private static final Map<String, TenantBinding> SESSION_TENANTS = new ConcurrentHashMap<>();

    private static volatile Instant lastSweep = Instant.EPOCH;

    /** 세션에 묶인 테넌트와 마지막 사용 시각. */
    private record TenantBinding(String tenantId, Instant lastSeen) {
        TenantBinding touch() {
            return new TenantBinding(tenantId, Instant.now());
        }
    }

    public McpTenantSessionFilter(TenantDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    /**
     * 지정한 MCP 세션의 테넌트를 반환한다.
     *
     * <p>CR-125: 인자 없는 정적 조회는 "마지막 연결자"를 반환해 교차 오염을 일으켰기 때문에
     * 제거했다. 호출자는 반드시 자기 세션 ID를 넘겨야 한다.</p>
     */
    public static String getTenantForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        TenantBinding binding = SESSION_TENANTS.get(sessionId);
        return binding == null ? null : binding.tenantId();
    }

    /** 테스트/운영 점검용: 현재 보관 중인 세션 매핑 수. */
    public static int trackedSessionCount() {
        return SESSION_TENANTS.size();
    }

    /** 테스트 격리용: 세션 매핑 전체 비우기. */
    public static void clearAllSessions() {
        SESSION_TENANTS.clear();
        lastSweep = Instant.EPOCH;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        String path = req.getRequestURI();

        // CR-124: SSE 하위경로(/sse, /message)와 Streamable 단일 엔드포인트(/admin-mcp)를 모두 커버.
        if (!path.startsWith("/admin-mcp")) {
            chain.doFilter(request, response);
            return;
        }

        sweepExpiredIfDue();

        String sessionId = req.getHeader(SESSION_ID_HEADER);
        String tenantParam = trimToNull(req.getParameter("tenant_id"));

        // 이 요청이 사용할 테넌트를 결정한다.
        //  1) tenant_id 파라미터가 실려오면 그것을 신뢰한다(연결/재지정 시점).
        //  2) 없으면 이 요청의 세션 ID에 묶인 테넌트를 쓴다. 다른 세션 값은 절대 보지 않는다.
        String tenantId = tenantParam;
        if (tenantId == null && sessionId != null) {
            tenantId = getTenantForSession(sessionId);
            if (tenantId != null) {
                touchSession(sessionId);
            }
        }

        if (tenantParam != null) {
            // 응답 시작 전에 DataSource 가 준비되어야 함
            dataSourceManager.getTenantDataSource(tenantParam);
            if (sessionId != null) {
                bindSession(sessionId, tenantParam);
            }
            log.info("MCP connected: tenant '{}', DataSource ensured (path={}, session={})",
                    tenantParam, path, abbreviate(sessionId));
        }

        boolean tenantSet = false;
        try {
            if (tenantId != null && TenantContext.getTenantId() == null) {
                TenantContext.setTenantId(tenantId);
                tenantSet = true;
                log.debug("MCP request: set tenant '{}' (path={}, session={})",
                        tenantId, path, abbreviate(sessionId));
            }

            chain.doFilter(request, response);
        } finally {
            // CR-125: DELETE 는 세션 종료 신호다. 해당 매핑을 즉시 제거한다.
            if ("DELETE".equalsIgnoreCase(req.getMethod()) && sessionId != null) {
                unbindSession(sessionId);
                log.info("MCP session unbound on DELETE: session={}", abbreviate(sessionId));
            }

            // CR-125: initialize 요청은 세션 ID가 응답 헤더로 처음 발급된다.
            // 요청 헤더엔 없었으므로, 체인 처리 후 응답 헤더를 읽어 그때 매핑을 심는다.
            if (sessionId == null && tenantParam != null && response instanceof HttpServletResponse res) {
                String issued = trimToNull(res.getHeader(SESSION_ID_HEADER));
                if (issued != null) {
                    bindSession(issued, tenantParam);
                    log.info("MCP session bound: session={} → tenant '{}'",
                            abbreviate(issued), tenantParam);
                }
            }

            // CR-124: Streamable 은 요청마다 스레드가 재사용될 수 있어, 이 필터가 심은 값은
            // 같은 요청 범위에서 되돌린다(누수 방지). 원래 값이 있던 경우는 건드리지 않는다.
            if (tenantSet) {
                TenantContext.clear();
            }
        }
    }

    private static void bindSession(String sessionId, String tenantId) {
        SESSION_TENANTS.put(sessionId, new TenantBinding(tenantId, Instant.now()));
    }

    private static void touchSession(String sessionId) {
        SESSION_TENANTS.computeIfPresent(sessionId, (k, v) -> v.touch());
    }

    /** 세션 종료(DELETE) 시 매핑 제거. 누수 방지. */
    static void unbindSession(String sessionId) {
        if (sessionId != null) {
            SESSION_TENANTS.remove(sessionId);
        }
    }

    /**
     * TTL 지난 매핑을 정리한다. 클라이언트가 DELETE 없이 끊는 경우가 있어
     * 세션 종료 신호만으로는 누수를 막지 못한다.
     */
    private static void sweepExpiredIfDue() {
        Instant now = Instant.now();
        if (Duration.between(lastSweep, now).compareTo(SWEEP_INTERVAL) < 0) {
            return;
        }
        lastSweep = now;

        Instant cutoff = now.minus(SESSION_TTL);
        int removed = 0;
        for (Iterator<Map.Entry<String, TenantBinding>> it = SESSION_TENANTS.entrySet().iterator();
             it.hasNext(); ) {
            if (it.next().getValue().lastSeen().isBefore(cutoff)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("MCP session mappings swept: {} expired, {} remain", removed, SESSION_TENANTS.size());
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 로그에 세션 ID 전체를 남기지 않는다. */
    private static String abbreviate(String sessionId) {
        if (sessionId == null) {
            return "none";
        }
        return sessionId.length() <= 8 ? sessionId : sessionId.substring(0, 8) + "…";
    }
}
