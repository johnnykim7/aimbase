package com.platform.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-172: 위젯 파일(/widget/**)도 widget.allowed-origins 로 교차 출처 허용을 판정한다.
 */
class CorsConfigTest {

    private static final String ALLOWED = "http://59.8.160.12:3201";
    private static final String DENIED = "http://evil.example.com";
    private static final String ESM = "/widget/v1/aimbase-chat.esm.js";

    private CorsConfigurationSource source;

    @BeforeEach
    void setUp() {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getStringList(eq("widget.allowed-origins"), any())).thenReturn(List.of(ALLOWED));
        source = new CorsConfig(settings).corsConfigurationSource();
    }

    private MockHttpServletResponse process(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        CorsConfiguration cfg = source.getCorsConfiguration(req);
        new DefaultCorsProcessor().processRequest(cfg, req, res);
        return res;
    }

    private MockHttpServletRequest request(String method, String uri, String origin) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setServerName("59.8.160.12");
        req.setServerPort(8280);
        if (origin != null) req.addHeader(HttpHeaders.ORIGIN, origin);
        return req;
    }

    @Test
    void widgetFile_allowedOrigin_getsAllowHeader() throws Exception {
        MockHttpServletResponse res = process(request("GET", ESM, ALLOWED));
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED);
    }

    @Test
    void widgetFile_preflightFromAllowedOrigin_passes() throws Exception {
        MockHttpServletRequest req = request("OPTIONS", ESM, ALLOWED);
        req.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        MockHttpServletResponse res = process(req);
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED);
    }

    @Test
    void widgetChunk_allowedOrigin_getsAllowHeader() throws Exception {
        MockHttpServletResponse res = process(request("GET", "/widget/v1/chunk-4MSYYEEA.js", ALLOWED));
        assertThat(res.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(ALLOWED);
    }

    @Test
    void widgetFile_deniedOrigin_getsNoAllowHeader() throws Exception {
        MockHttpServletResponse res = process(request("GET", ESM, DENIED));
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    void widgetFile_noOrigin_isNotCorsAndPasses() throws Exception {
        MockHttpServletResponse res = process(request("GET", "/widget/v1/aimbase-chat.umd.global.js", null));
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    void adminApi_staysOutOfScope() {
        assertThat(source.getCorsConfiguration(request("GET", "/api/v1/admin/users", ALLOWED))).isNull();
        assertThat(source.getCorsConfiguration(request("GET", "/api/v1/platform/tenants", ALLOWED))).isNull();
    }
}
