package com.platform.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-124: Streamable 은 단일 엔드포인트라 "/sse" 를 덧붙이면 안 된다.
 */
class Cr124StreamableUrlSplitTest {

    @Test
    void streamableSplit_preservesPath_withoutAppendingSse() {
        var s = MCPServerClient.splitBaseAndPath("http://agent-host:8296/mcp");
        assertThat(s.baseUri()).isEqualTo("http://agent-host:8296");
        assertThat(s.sseEndpoint()).isEqualTo("/mcp");
    }

    @Test
    void streamableSplit_handlesContextPath() {
        var s = MCPServerClient.splitBaseAndPath("http://host:8183/api/mcp");
        assertThat(s.baseUri()).isEqualTo("http://host:8183");
        assertThat(s.sseEndpoint()).isEqualTo("/api/mcp");
    }

    @Test
    void streamableSplit_defaultsToMcp_whenNoPath() {
        assertThat(MCPServerClient.splitBaseAndPath("http://host:8296").sseEndpoint()).isEqualTo("/mcp");
    }

    @Test
    void sseSplit_stillAppendsSse_forSidecars() {
        // Python 사이드카는 그대로 SSE — 기존 동작 보존 확인
        assertThat(MCPServerClient.splitBaseAndSsePath("http://host:8000/api/mcp").sseEndpoint())
                .isEqualTo("/api/mcp/sse");
    }
}
