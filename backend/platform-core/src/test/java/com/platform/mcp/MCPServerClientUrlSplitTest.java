package com.platform.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCPServerClient.splitBaseAndSsePath 단위 테스트.
 *
 * SDK 0.10.0 의 HttpClientSseClientTransport 는 baseUri + sseEndpoint(절대 경로) 형태로 호출하고,
 * Java URI.resolve(절대경로) 는 base path 를 덮어쓰므로 — DB 의 url 컬럼이 context path 를 포함하면
 * 그대로 builder 에 넘기면 SSE 호출이 root("/sse") 로 가서 404 가 난다.
 *
 * 본 헬퍼는 단일 url 입력을 (scheme://authority, path+"/sse") 쌍으로 분리하여 그 버그를 우회한다.
 */
class MCPServerClientUrlSplitTest {

    @Test
    void contextPath_있는_url_은_path를_sseEndpoint_로_분리() {
        var s = MCPServerClient.splitBaseAndSsePath("http://host.docker.internal:8183/api/mcp");
        assertThat(s.baseUri()).isEqualTo("http://host.docker.internal:8183");
        assertThat(s.sseEndpoint()).isEqualTo("/api/mcp/sse");
    }

    @Test
    void url_이_이미_sse_로_끝나면_그대로_사용() {
        var s = MCPServerClient.splitBaseAndSsePath("http://host:8183/api/mcp/sse");
        assertThat(s.baseUri()).isEqualTo("http://host:8183");
        assertThat(s.sseEndpoint()).isEqualTo("/api/mcp/sse");
    }

    @Test
    void path_없는_url_은_기본_sse_사용() {
        var s = MCPServerClient.splitBaseAndSsePath("http://host:8183");
        assertThat(s.baseUri()).isEqualTo("http://host:8183");
        assertThat(s.sseEndpoint()).isEqualTo("/sse");
    }

    @Test
    void path_가_root_뿐인_url_은_기본_sse_사용() {
        var s = MCPServerClient.splitBaseAndSsePath("http://host:8183/");
        assertThat(s.baseUri()).isEqualTo("http://host:8183");
        assertThat(s.sseEndpoint()).isEqualTo("/sse");
    }

    @Test
    void path_가_trailing_slash_로_끝나도_정상_정규화() {
        var s = MCPServerClient.splitBaseAndSsePath("http://host:8183/api/mcp/");
        assertThat(s.baseUri()).isEqualTo("http://host:8183");
        assertThat(s.sseEndpoint()).isEqualTo("/api/mcp/sse");
    }

    @Test
    void https_와_user_info_authority_도_유지() {
        var s = MCPServerClient.splitBaseAndSsePath("https://user:pw@example.com/svc");
        assertThat(s.baseUri()).isEqualTo("https://user:pw@example.com");
        assertThat(s.sseEndpoint()).isEqualTo("/svc/sse");
    }

    @Test
    void scheme_없는_url_은_예외() {
        assertThatThrownBy(() -> MCPServerClient.splitBaseAndSsePath("/api/mcp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void 잘못된_url_은_예외() {
        assertThatThrownBy(() -> MCPServerClient.splitBaseAndSsePath("ht tp://bad url"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
