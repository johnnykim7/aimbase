package com.platform.api;

import com.platform.auth.UserPrincipal;
import com.platform.session.ChatOwner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CR-206 TC-CHT-161 3, 4 (BIZ-291): 지식 자료 API 는 위젯 토큰이면 청크 원문 조회를 빼고 403 widget_forbidden. */
class KnowledgeWidgetGuardTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // 등록은 KnowledgeWidgetGuardConfig 와 같은 경로, 같은 예외 경로다
        MappedInterceptor guard = new MappedInterceptor(new String[]{KnowledgeWidgetGuard.PATH},
                new String[]{KnowledgeWidgetGuard.CHUNK_PATH}, new KnowledgeWidgetGuard());
        mvc = MockMvcBuilders.standaloneSetup(new Stub())
                .addInterceptors(guard)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("TC-CHT-161 3 위젯 토큰의 목록, 상세, 검색은 403 widget_forbidden")
    void widget_token_is_forbidden_on_management_endpoints() throws Exception {
        signIn(widget());

        mvc.perform(get("/api/v1/knowledge-sources")).andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("widget_forbidden")));
        mvc.perform(get("/api/v1/knowledge-sources/kb_a")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/knowledge-sources/search")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TC-CHT-161 1 위젯 토큰의 청크 원문 조회는 가로채지 않는다")
    void widget_token_still_reads_chunks() throws Exception {
        signIn(widget());

        mvc.perform(get("/api/v1/knowledge-sources/kb_a/chunks/c-1")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("TC-CHT-161 4 로그인의 목록은 지금처럼 200")
    void login_passes() throws Exception {
        signIn(new UserPrincipal("user-1", "a@example.com", "tenant-a", "ADMIN", Map.of()));

        mvc.perform(get("/api/v1/knowledge-sources")).andExpect(status().isOk());
    }

    private static UserPrincipal widget() {
        return new UserPrincipal("widget-sub", null, "tenant-a", ChatOwner.WIDGET_ROLE, Map.of());
    }

    private static void signIn(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @RestController
    static class Stub {
        @GetMapping("/api/v1/knowledge-sources")
        String list() { return "list"; }

        @GetMapping("/api/v1/knowledge-sources/{id}")
        String get(@PathVariable String id) { return id; }

        @PostMapping("/api/v1/knowledge-sources/search")
        String search() { return "search"; }

        @GetMapping("/api/v1/knowledge-sources/{sourceId}/chunks/{chunkId}")
        String chunk(@PathVariable String sourceId, @PathVariable String chunkId) { return sourceId + chunkId; }
    }
}
