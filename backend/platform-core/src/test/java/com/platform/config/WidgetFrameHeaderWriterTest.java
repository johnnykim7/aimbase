package com.platform.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-203 (BIZ-300): 전용 페이지만 frame-ancestors, 나머지는 X-Frame-Options: DENY.
 */
class WidgetFrameHeaderWriterTest {

    private static final String FRAME_PAGE = "/widget/v1/frame.html";

    private PlatformSettingsService settings;
    private WidgetFrameHeaderWriter writer;

    @BeforeEach
    void setUp() {
        settings = mock(PlatformSettingsService.class);
        writer = new WidgetFrameHeaderWriter(settings);
    }

    private MockHttpServletResponse write(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        writer.writeHeaders(request, response);
        return response;
    }

    @Test
    @DisplayName("TC-FRM-004 전용 페이지에는 허용 주소 목록의 frame-ancestors 가 붙고 X-Frame-Options 가 없다")
    void framePage_allowedOrigins() {
        when(settings.getStringList(eq("widget.allowed-origins"), any()))
                .thenReturn(List.of("https://a.site", "https://b.site"));

        MockHttpServletResponse response = write(FRAME_PAGE);

        assertThat(response.getHeader("Content-Security-Policy"))
                .isEqualTo("frame-ancestors https://a.site https://b.site");
        assertThat(response.getHeader("X-Frame-Options")).isNull();
    }

    @Test
    @DisplayName("TC-FRM-004 버전 자리는 어느 값이든 전용 페이지로 본다")
    void framePage_anyVersion() {
        when(settings.getStringList(eq("widget.allowed-origins"), any())).thenReturn(List.of("https://a.site"));

        assertThat(write("/widget/v2/frame.html").getHeader("Content-Security-Policy"))
                .isEqualTo("frame-ancestors https://a.site");
        assertThat(write("/widget/1.4.0/frame.html").getHeader("Content-Security-Policy"))
                .isEqualTo("frame-ancestors https://a.site");
    }

    @Test
    @DisplayName("TC-FRM-005 허용 주소가 비어 있으면 frame-ancestors 'none'")
    void framePage_noAllowedOrigins() {
        when(settings.getStringList(eq("widget.allowed-origins"), any())).thenReturn(List.of());

        MockHttpServletResponse response = write(FRAME_PAGE);

        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("frame-ancestors 'none'");
        assertThat(response.getHeader("X-Frame-Options")).isNull();
    }

    @Test
    @DisplayName("TC-FRM-006 다른 경로는 X-Frame-Options: DENY 이고 CSP 가 없다")
    void otherPaths_deny() {
        for (String uri : List.of("/widget/v1/index.html", "/widget/v1/aimbase-chat.umd.js", "/api/v1/chat/stream", "/")) {
            MockHttpServletResponse response = write(uri);
            assertThat(response.getHeader("X-Frame-Options")).as(uri).isEqualTo("DENY");
            assertThat(response.getHeader("Content-Security-Policy")).as(uri).isNull();
        }
    }

    @Test
    @DisplayName("TC-FRM-006 이미 X-Frame-Options 가 있으면 덧쓰지 않는다")
    void otherPaths_keepExistingFrameOptions() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ping");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("X-Frame-Options", "SAMEORIGIN");

        writer.writeHeaders(request, response);

        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
    }
}
