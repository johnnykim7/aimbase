package com.platform.session;

import com.platform.auth.UserPrincipal;
import com.platform.repository.KnowledgeSourceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** CR-206 TC-CHT-159, TC-CHT-160, TC-CHT-161 (BIZ-290): 허용 자료 목록과 대화 요청, 청크 조회의 대조. */
@ExtendWith(MockitoExtension.class)
class RagSourceAccessTest {

    @Mock private KnowledgeSourceRepository sources;

    private RagSourceAccess access;

    @BeforeEach
    void setUp() {
        access = new RagSourceAccess(sources);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("TC-CHT-160 1 허용 목록 토큰은 목록 안 자료를 그대로 쓴다")
    void bound_token_uses_a_source_inside_the_list() {
        signIn(widget(List.of("kb_a", "kb_b")));
        when(sources.existsById("kb_b")).thenReturn(true);

        assertThat(access.resolve("kb_b")).isEqualTo("kb_b");
    }

    @Test
    @DisplayName("TC-CHT-160 3 자료 ID 를 비우면 첫 자료다")
    void bound_token_without_source_uses_the_first() {
        signIn(widget(List.of("kb_a", "kb_b")));
        when(sources.existsById("kb_a")).thenReturn(true);

        assertThat(access.resolve(null)).isEqualTo("kb_a");
        assertThat(access.resolve("  ")).isEqualTo("kb_a");
    }

    @Test
    @DisplayName("TC-CHT-160 2 목록 밖 자료는 있는지 보지도 않고 404 rag_source_not_found")
    void bound_token_outside_the_list_is_404_without_looking() {
        signIn(widget(List.of("kb_a")));

        assertRejected(() -> access.resolve("kb_other"), HttpStatus.NOT_FOUND, "rag_source_not_found");
        verify(sources, never()).existsById(any());
    }

    @Test
    @DisplayName("TC-CHT-160 목록 없는 위젯 토큰과 로그인은 자료 ID 가 없으면 검색하지 않는다")
    void unbound_requests_without_source_search_nothing() {
        signIn(widget(null));
        assertThat(access.resolve(null)).isNull();

        signIn(login());
        assertThat(access.resolve("")).isNull();

        SecurityContextHolder.clearContext();
        assertThat(access.resolve(null)).isNull();
        verifyNoInteractions(sources);
    }

    @Test
    @DisplayName("TC-CHT-160 4 목록 없는 위젯 토큰이라도 테넌트에 없는 자료는 404")
    void unbound_widget_missing_source_is_404() {
        signIn(widget(null));

        assertRejected(() -> access.resolve("kb_x"), HttpStatus.NOT_FOUND, "rag_source_not_found");
    }

    @Test
    @DisplayName("TC-CHT-160 5 로그인은 테넌트에 있는 자료를 그대로 쓴다")
    void login_with_existing_source_uses_it() {
        signIn(login());
        when(sources.existsById("kb_a")).thenReturn(true);

        assertThat(access.resolve("kb_a")).isEqualTo("kb_a");
    }

    @Test
    @DisplayName("TC-CHT-161 1, 2 청크 조회는 목록 안 자료만 읽고 목록 밖은 404")
    void chunk_read_follows_the_list() {
        signIn(widget(List.of("kb_a")));
        assertThatCode(() -> access.requireReadable("kb_a")).doesNotThrowAnyException();
        assertRejected(() -> access.requireReadable("kb_b"), HttpStatus.NOT_FOUND, "rag_source_not_found");

        signIn(widget(null));
        assertThatCode(() -> access.requireReadable("kb_b")).doesNotThrowAnyException();
        signIn(login());
        assertThatCode(() -> access.requireReadable("kb_b")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TC-CHT-159 2 빈 값과 중복을 빼고 남은 자료마다 테넌트에 있는지 본다")
    void for_token_drops_blank_and_duplicates() {
        when(sources.existsById("kb_a")).thenReturn(true);
        when(sources.existsById("kb_b")).thenReturn(true);

        assertThat(access.forToken(Arrays.asList("kb_a", " ", "kb_b", "kb_a", null)))
                .containsExactly("kb_a", "kb_b");
    }

    @Test
    @DisplayName("TC-CHT-159 3, 4, 5 테넌트에 없는 자료, 101자, 21개는 400 widget_token_invalid:rag_source_id")
    void for_token_rejects_unknown_long_or_too_many() {
        assertRejected(() -> access.forToken(List.of("kb_x")), HttpStatus.BAD_REQUEST, "widget_token_invalid:rag_source_id");
        assertRejected(() -> access.forToken(List.of("a".repeat(101))), HttpStatus.BAD_REQUEST, "widget_token_invalid:rag_source_id");
        List<String> tooMany = IntStream.rangeClosed(1, 21).mapToObj(i -> "kb_" + i).toList();
        assertRejected(() -> access.forToken(tooMany), HttpStatus.BAD_REQUEST, "widget_token_invalid:rag_source_id");
        verify(sources, never()).existsById("a".repeat(101));
        verify(sources, never()).existsById("kb_1");
    }

    @Test
    @DisplayName("TC-CHT-159 6 칸이 없거나 비면 허용 자료 없는 토큰이다")
    void for_token_without_ids_is_empty() {
        assertThat(access.forToken(null)).isEmpty();
        assertThat(access.forToken(List.of())).isEmpty();
        assertThat(access.forToken(List.of(" "))).isEmpty();
        verifyNoInteractions(sources);
    }

    private static UserPrincipal widget(List<String> ragSourceIds) {
        Map<String, Object> info = ragSourceIds == null ? Map.of() : Map.of(ChatOwner.RAG_SOURCE_IDS_KEY, ragSourceIds);
        return new UserPrincipal("widget-sub", null, "tenant-a", ChatOwner.WIDGET_ROLE, info);
    }

    private static UserPrincipal login() {
        return new UserPrincipal("user-1", "a@example.com", "tenant-a", "ADMIN", Map.of());
    }

    private static void signIn(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                       HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOf(ChatRejectedException.class).hasMessage(code)
                .extracting("status").isEqualTo(status);
    }
}
