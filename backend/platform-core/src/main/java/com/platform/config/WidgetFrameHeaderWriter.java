package com.platform.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.util.AntPathMatcher;

import java.util.List;

/**
 * CR-203 (BIZ-300): 채팅창 전용 페이지(/widget/{버전}/frame.html)만 허용 주소 목록의 화면에 끼워진다.
 * 전용 페이지 응답에는 Content-Security-Policy frame-ancestors 를 붙이고 X-Frame-Options 를 빼며,
 * 그 밖의 응답은 지금처럼 X-Frame-Options: DENY 다. 허용 목록은 widget.allowed-origins 를 요청마다 읽는다.
 */
public class WidgetFrameHeaderWriter implements HeaderWriter {

    static final String FRAME_PAGE_PATTERN = "/widget/*/frame.html";
    static final String ALLOWED_ORIGINS_KEY = "widget.allowed-origins";
    static final String CSP_HEADER = "Content-Security-Policy";
    static final String FRAME_OPTIONS_HEADER = "X-Frame-Options";

    private final PlatformSettingsService settings;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public WidgetFrameHeaderWriter(PlatformSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
        if (isFramePage(request)) {
            List<String> origins = settings.getStringList(ALLOWED_ORIGINS_KEY, List.of());
            String ancestors = origins.isEmpty() ? "'none'" : String.join(" ", origins);
            response.setHeader(CSP_HEADER, "frame-ancestors " + ancestors);
            return;
        }
        if (!response.containsHeader(FRAME_OPTIONS_HEADER)) {
            response.setHeader(FRAME_OPTIONS_HEADER, "DENY");
        }
    }

    boolean isFramePage(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) return false;
        String contextPath = request.getContextPath();
        String path = contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length()) : uri;
        return matcher.match(FRAME_PAGE_PATTERN, path);
    }
}
