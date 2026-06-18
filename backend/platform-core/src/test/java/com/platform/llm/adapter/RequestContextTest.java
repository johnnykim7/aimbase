package com.platform.llm.adapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestContextTest {

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("setAgentId/getAgentId 라운드트립")
    void roundtrip() {
        assertThat(RequestContext.getAgentId()).isNull();
        RequestContext.setAgentId("agent-1");
        assertThat(RequestContext.getAgentId()).isEqualTo("agent-1");
    }

    @Test
    @DisplayName("clear 호출 후 null")
    void clearRemoves() {
        RequestContext.setAgentId("agent-1");
        RequestContext.clear();
        assertThat(RequestContext.getAgentId()).isNull();
    }

    @Test
    @DisplayName("requireAgentId — 값 있으면 그대로 반환")
    void requireOk() {
        RequestContext.setAgentId("agent-2");
        assertThat(RequestContext.requireAgentId()).isEqualTo("agent-2");
    }

    @Test
    @DisplayName("requireAgentId — null 이면 400")
    void requireMissing() {
        assertThatThrownBy(RequestContext::requireAgentId)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("X-Aimbase-Agent-Id header required");
    }

    @Test
    @DisplayName("requireAgentId — 빈 문자열도 400")
    void requireBlank() {
        RequestContext.setAgentId("   ");
        assertThatThrownBy(RequestContext::requireAgentId)
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("CR-117: setWorkspacePath/getWorkspacePath 라운드트립")
    void workspacePathRoundtrip() {
        assertThat(RequestContext.getWorkspacePath()).isNull();
        RequestContext.setWorkspacePath("/data/workspace/t/runs/abc");
        assertThat(RequestContext.getWorkspacePath()).isEqualTo("/data/workspace/t/runs/abc");
    }

    @Test
    @DisplayName("CR-117: clear 는 agentId 와 workspacePath 를 함께 정리")
    void clearRemovesWorkspacePath() {
        RequestContext.setAgentId("agent-1");
        RequestContext.setWorkspacePath("/data/workspace/t/runs/abc");
        RequestContext.clear();
        assertThat(RequestContext.getAgentId()).isNull();
        assertThat(RequestContext.getWorkspacePath()).isNull();
    }

    @Test
    @DisplayName("ThreadLocal — 다른 스레드는 독립")
    void threadLocalIsolation() throws Exception {
        RequestContext.setAgentId("agent-main");
        String[] childValue = {"unset"};
        Thread t = new Thread(() -> childValue[0] = RequestContext.getAgentId());
        t.start();
        t.join();
        assertThat(childValue[0]).isNull();
        assertThat(RequestContext.getAgentId()).isEqualTo("agent-main");
    }
}
