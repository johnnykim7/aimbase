package com.platform.mcp.admin;

import com.platform.tenant.TenantContext;
import com.platform.tenant.TenantDataSourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * CR-125 회귀 방지: MCP 세션별로 테넌트가 격리되는지 검증한다.
 *
 * <p>수정 전에는 테넌트가 {@code static volatile} 단일 필드에 담겨,
 * 나중에 연결한 테넌트가 앞선 테넌트의 후속 요청까지 가로챘다(BIZ-003 위반).
 * 아래 테스트들은 그 교차 오염이 재발하면 실패한다.</p>
 */
class Cr125TenantCrossTalkTest {

    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final TenantDataSourceManager dsm = mock(TenantDataSourceManager.class);
    private final McpTenantSessionFilter filter = new McpTenantSessionFilter(dsm);

    @BeforeEach
    void setUp() {
        McpTenantSessionFilter.clearAllSessions();
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        McpTenantSessionFilter.clearAllSessions();
        TenantContext.clear();
    }

    private String runAndCapture(MockHttpServletRequest req) throws Exception {
        return runAndCapture(req, new MockHttpServletResponse());
    }

    /** 필터 체인 안에서 관측된 TenantContext 값을 잡아낸다. */
    private String runAndCapture(MockHttpServletRequest req, MockHttpServletResponse res) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        var chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest rq, jakarta.servlet.ServletResponse rs) {
                seen.set(TenantContext.getTenantId());
            }
        };
        filter.doFilter(req, res, chain);
        return seen.get();
    }

    /** 세션 ID를 달고 오는 후속 요청 (tenant_id 파라미터 없음). */
    private MockHttpServletRequest followup(String sessionId) {
        var req = new MockHttpServletRequest("POST", "/admin-mcp");
        req.addHeader(SESSION_HEADER, sessionId);
        return req;
    }

    /** 연결 요청: tenant_id 파라미터 + 이미 발급된 세션 ID. */
    private MockHttpServletRequest connect(String sessionId, String tenantId) {
        var req = new MockHttpServletRequest("POST", "/admin-mcp");
        req.addHeader(SESSION_HEADER, sessionId);
        req.setParameter("tenant_id", tenantId);
        return req;
    }

    /**
     * 핵심 회귀 테스트: B가 끼어들어도 A의 후속 요청은 A로 간다.
     * 수정 전에는 여기서 tenant_b 가 관측됐다.
     */
    @Test
    void interleavedSessions_eachKeepsOwnTenant() throws Exception {
        runAndCapture(connect("sess-A", "tenant_a"));
        runAndCapture(connect("sess-B", "tenant_b"));

        assertThat(runAndCapture(followup("sess-A"))).isEqualTo("tenant_a");
        assertThat(runAndCapture(followup("sess-B"))).isEqualTo("tenant_b");
        // 순서를 바꿔도 유지된다
        assertThat(runAndCapture(followup("sess-A"))).isEqualTo("tenant_a");
    }

    /** 도구 핸들러가 쓰는 조회 경로도 세션별로 갈린다. */
    @Test
    void sessionLookup_isPerSession() throws Exception {
        runAndCapture(connect("sess-A", "tenant_a"));
        runAndCapture(connect("sess-B", "tenant_b"));

        assertThat(McpTenantSessionFilter.getTenantForSession("sess-A")).isEqualTo("tenant_a");
        assertThat(McpTenantSessionFilter.getTenantForSession("sess-B")).isEqualTo("tenant_b");
        // 모르는 세션은 아무 테넌트도 주지 않는다 (임의 테넌트로 흘러들지 않음)
        assertThat(McpTenantSessionFilter.getTenantForSession("sess-UNKNOWN")).isNull();
        assertThat(McpTenantSessionFilter.getTenantForSession(null)).isNull();
    }

    /**
     * initialize 구간: 요청엔 세션 ID가 없고 SDK가 응답 헤더로 발급한다.
     * 필터는 체인 처리 후 응답 헤더를 읽어 매핑을 심어야 한다.
     */
    @Test
    void initializeRequest_bindsTenantFromResponseHeader() throws Exception {
        var req = new MockHttpServletRequest("POST", "/admin-mcp");
        req.setParameter("tenant_id", "tenant_init");

        var res = new MockHttpServletResponse();
        // SDK 가 세션을 만들어 응답 헤더로 내려주는 상황을 모사
        var chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest rq, jakarta.servlet.ServletResponse rs) {
                ((MockHttpServletResponse) rs).setHeader(SESSION_HEADER, "sess-NEW");
            }
        };
        filter.doFilter(req, res, chain);

        // 발급된 세션으로 매핑이 심겼는지
        assertThat(McpTenantSessionFilter.getTenantForSession("sess-NEW")).isEqualTo("tenant_init");
        // 후속 요청이 그 테넌트를 그대로 받는지
        assertThat(runAndCapture(followup("sess-NEW"))).isEqualTo("tenant_init");
    }

    /** 세션 ID도 tenant_id도 없으면 어떤 테넌트도 심지 않는다(전역 폴백 제거 확인). */
    @Test
    void unknownSession_getsNoTenant() throws Exception {
        runAndCapture(connect("sess-A", "tenant_a"));

        var bare = new MockHttpServletRequest("POST", "/admin-mcp");
        assertThat(runAndCapture(bare)).isNull();

        assertThat(runAndCapture(followup("sess-GHOST"))).isNull();
    }

    /** DELETE(세션 종료)로 매핑이 제거된다. */
    @Test
    void deleteRequest_unbindsSession() throws Exception {
        runAndCapture(connect("sess-A", "tenant_a"));
        assertThat(McpTenantSessionFilter.getTenantForSession("sess-A")).isEqualTo("tenant_a");

        var del = new MockHttpServletRequest("DELETE", "/admin-mcp");
        del.addHeader(SESSION_HEADER, "sess-A");
        runAndCapture(del);

        assertThat(McpTenantSessionFilter.getTenantForSession("sess-A")).isNull();
        assertThat(runAndCapture(followup("sess-A"))).isNull();
    }

    /** 동시 요청에서도 각 세션이 자기 테넌트만 본다. */
    @Test
    void concurrentSessions_doNotCrossTalk() throws Exception {
        int sessions = 16;
        for (int i = 0; i < sessions; i++) {
            runAndCapture(connect("sess-" + i, "tenant_" + i));
        }

        var start = new CountDownLatch(1);
        var done = new CountDownLatch(sessions);
        var mismatches = new java.util.concurrent.ConcurrentLinkedQueue<String>();

        for (int i = 0; i < sessions; i++) {
            final int idx = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int r = 0; r < 50; r++) {
                        String observed = runAndCapture(followup("sess-" + idx));
                        if (!("tenant_" + idx).equals(observed)) {
                            mismatches.add("sess-" + idx + " saw " + observed);
                        }
                    }
                } catch (Exception e) {
                    mismatches.add("sess-" + idx + " threw " + e);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(mismatches).isEmpty();
    }
}
