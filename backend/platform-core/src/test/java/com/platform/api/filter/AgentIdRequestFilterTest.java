package com.platform.api.filter;

import com.platform.llm.adapter.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentIdRequestFilterTest {

    private AgentIdRequestFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new AgentIdRequestFilter();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        RequestContext.clear();
    }

    @Test
    @DisplayName("X-Aimbase-Agent-Id 헤더가 있으면 chain 진행 중 RequestContext 에 set")
    void headerPresent() throws Exception {
        when(request.getHeader("X-Aimbase-Agent-Id")).thenReturn("agent-abc");

        // chain.doFilter 호출 시점에 RequestContext 값이 셋됐는지 검증
        String[] capturedDuringChain = {"unset"};
        org.mockito.Mockito.doAnswer(inv -> {
            capturedDuringChain[0] = RequestContext.getAgentId();
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        assertThat(capturedDuringChain[0]).isEqualTo("agent-abc");
        // 종료 후 clear 확인
        assertThat(RequestContext.getAgentId()).isNull();
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    @DisplayName("헤더 없으면 RequestContext 미설정 (Adapter 호출 시점에 검증)")
    void headerMissing() throws Exception {
        when(request.getHeader("X-Aimbase-Agent-Id")).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertThat(RequestContext.getAgentId()).isNull();
        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    @DisplayName("헤더 빈 문자열도 미설정")
    void headerBlank() throws Exception {
        when(request.getHeader("X-Aimbase-Agent-Id")).thenReturn("   ");

        filter.doFilter(request, response, chain);

        assertThat(RequestContext.getAgentId()).isNull();
    }

    @Test
    @DisplayName("trim 처리 후 set")
    void headerTrimmed() throws Exception {
        when(request.getHeader("X-Aimbase-Agent-Id")).thenReturn("  agent-x  ");

        String[] captured = {"unset"};
        org.mockito.Mockito.doAnswer(inv -> {
            captured[0] = RequestContext.getAgentId();
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);
        assertThat(captured[0]).isEqualTo("agent-x");
    }

    @Test
    @DisplayName("CR-117: X-Aimbase-Workspace-Path 헤더가 chain 진행 중 RequestContext 에 set")
    void workspaceHeaderPresent() throws Exception {
        when(request.getHeader("X-Aimbase-Workspace-Path"))
                .thenReturn("/data/workspace/bidding_system/runs/316d510c");

        String[] captured = {"unset"};
        org.mockito.Mockito.doAnswer(inv -> {
            captured[0] = RequestContext.getWorkspacePath();
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        assertThat(captured[0]).isEqualTo("/data/workspace/bidding_system/runs/316d510c");
        // 종료 후 clear 확인
        assertThat(RequestContext.getWorkspacePath()).isNull();
    }

    @Test
    @DisplayName("CR-117: workspace 헤더 없으면 미설정 (default/general 폴백 경로)")
    void workspaceHeaderMissing() throws Exception {
        when(request.getHeader("X-Aimbase-Workspace-Path")).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertThat(RequestContext.getWorkspacePath()).isNull();
    }

    @Test
    @DisplayName("chain 예외가 던져져도 finally 에서 clear")
    void exceptionInChainStillClears() {
        when(request.getHeader("X-Aimbase-Agent-Id")).thenReturn("agent-zzz");

        try {
            org.mockito.Mockito.doThrow(new RuntimeException("downstream error"))
                    .when(chain).doFilter(request, response);
            filter.doFilter(request, response, chain);
        } catch (Exception ignored) {}

        assertThat(RequestContext.getAgentId()).isNull();
    }
}
