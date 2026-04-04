package com.platform.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * ProjectResolver 단위 테스트 (CR-021).
 * X-Project-Id 헤더 추출 + ProjectContext 설정/해제 검증.
 */
class ProjectResolverTest {

    private ProjectResolver resolver;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        resolver = new ProjectResolver();
        response = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        ProjectContext.clear();
    }

    @Test
    void doFilter_withProjectHeader_shouldSetContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Project-Id", "proj-1");
        request.setRequestURI("/api/v1/workflows");

        doAnswer(inv -> {
            assertThat(ProjectContext.getProjectId()).isEqualTo("proj-1");
            return null;
        }).when(chain).doFilter(request, response);

        resolver.doFilter(request, response, chain);

        // finally에서 clear
        assertThat(ProjectContext.getProjectId()).isNull();
    }

    @Test
    void doFilter_noHeader_shouldNotSetContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/workflows");

        doAnswer(inv -> {
            assertThat(ProjectContext.getProjectId()).isNull();
            return null;
        }).when(chain).doFilter(request, response);

        resolver.doFilter(request, response, chain);
    }

    @Test
    void doFilter_blankHeader_shouldNotSetContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Project-Id", "   ");
        request.setRequestURI("/api/v1/workflows");

        doAnswer(inv -> {
            assertThat(ProjectContext.getProjectId()).isNull();
            return null;
        }).when(chain).doFilter(request, response);

        resolver.doFilter(request, response, chain);
    }

    @Test
    void doFilter_chainThrows_shouldStillClearContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Project-Id", "proj-x");
        request.setRequestURI("/api/v1/workflows");

        doThrow(new ServletException("error")).when(chain).doFilter(request, response);

        try {
            resolver.doFilter(request, response, chain);
        } catch (ServletException ignored) {
        }

        assertThat(ProjectContext.getProjectId()).isNull();
    }

    @Test
    void doFilter_headerWithWhitespace_shouldTrim() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Project-Id", "  proj-2  ");
        request.setRequestURI("/api/v1/workflows");

        doAnswer(inv -> {
            assertThat(ProjectContext.getProjectId()).isEqualTo("proj-2");
            return null;
        }).when(chain).doFilter(request, response);

        resolver.doFilter(request, response, chain);
    }
}
