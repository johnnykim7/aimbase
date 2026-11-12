package com.platform.session;

import com.platform.auth.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TC-CHT-049 (CR-178 BIZ-170): 대화와 프로젝트의 주인. 위젯은 user_ref, 로그인은 사용자 id 이고
 * user_ref 없는 위젯 토큰은 주인이 없다.
 */
class ChatOwnerTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void widget_owner_is_user_ref_and_missing_user_ref_means_no_owner() {
        assertThat(ChatOwner.of(widget("u-77"))).isEqualTo("u-77");
        assertThat(ChatOwner.of(widget(null))).isNull();
        assertThat(ChatOwner.of(widget("  "))).isNull();
    }

    @Test
    void login_owner_is_user_id() {
        assertThat(ChatOwner.of(login())).isEqualTo("user-1");
    }

    @Test
    void current_reads_the_signed_in_principal() {
        assertThat(ChatOwner.current()).isNull();

        SecurityContextHolder.getContext().setAuthentication(auth(widget("u-77")));

        assertThat(ChatOwner.current()).isEqualTo("u-77");
    }

    @Test
    void login_session_written_before_cr178_with_email_still_belongs_to_the_user() {
        Authentication auth = auth(login());

        assertThat(ChatOwner.owns("user-1", auth)).isTrue();
        assertThat(ChatOwner.owns("a@example.com", auth)).isTrue();
        assertThat(ChatOwner.owns("user-2", auth)).isFalse();
    }

    @Test
    void widget_token_owns_only_its_user_ref_sessions() {
        assertThat(ChatOwner.owns("u-77", auth(widget("u-77")))).isTrue();
        assertThat(ChatOwner.owns("widget-sub", auth(widget("u-77")))).isFalse();
        assertThat(ChatOwner.owns("widget-sub", auth(widget(null)))).isFalse();
        assertThat(ChatOwner.owns(null, auth(widget("u-77")))).isFalse();
    }

    private static UserPrincipal widget(String userRef) {
        Map<String, Object> permissions = userRef == null ? Map.of() : Map.of(ChatOwner.USER_REF_KEY, userRef);
        return new UserPrincipal("widget-sub", null, "tenant-a", ChatOwner.WIDGET_ROLE, permissions);
    }

    private static UserPrincipal login() {
        return new UserPrincipal("user-1", "a@example.com", "tenant-a", "ADMIN", Map.of());
    }

    private static Authentication auth(UserPrincipal principal) {
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
