package com.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-124: Streamable 전환 후 MCP 요청은 하위경로 없이 단일 엔드포인트(POST /mcp)로 온다.
 * 기존 Security 매처가 그 "맨 경로"를 여전히 커버하는지 확인한다.
 */
class Cr124SecurityPathMatchTest {

    private boolean matches(String pattern, String uri) {
        var req = new MockHttpServletRequest("POST", uri);
        req.setServletPath(uri);
        return new AntPathRequestMatcher(pattern).matches(req);
    }

    @Test
    void mcpPattern_coversBareEndpoint() {
        // Streamable 은 /mcp/message 가 아니라 /mcp 로 온다
        assertThat(matches("/mcp/**", "/mcp")).isTrue();
        assertThat(matches("/mcp/**", "/mcp/")).isTrue();
    }

    @Test
    void adminMcpPattern_coversBareEndpoint() {
        assertThat(matches("/admin-mcp/**", "/admin-mcp")).isTrue();
    }
}
