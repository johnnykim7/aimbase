package com.platform.auth;

import org.springframework.security.core.Authentication;

/**
 * 위젯 토큰의 scope 검사. 위젯 토큰은 scopes 를 {@code SCOPE_*} 권한으로 옮기고 주체 역할을 {@code WIDGET} 으로 둔다
 * ({@link JwtAuthenticationFilter}). 로그인 사용자와 API 키는 scope 가 없으므로 검사하지 않는다.
 */
public final class WidgetScopes {

    public static final String WIDGET_ROLE = "WIDGET";

    private WidgetScopes() {}

    /** 위젯 토큰인데 그 scope 가 없으면 true. */
    public static boolean lacks(Authentication auth, String scope) {
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal p) || !WIDGET_ROLE.equals(p.getRole())) {
            return false;
        }
        String wanted = "SCOPE_" + scope;
        return auth.getAuthorities().stream().noneMatch(a -> wanted.equals(a.getAuthority()));
    }

    /** 감사 로그와 입장권에 남기는 사람. 위젯 토큰이면 user_ref(없으면 subject), 로그인 사용자면 그 id. */
    public static String principalId(Authentication auth) {
        if (auth == null) return null;
        if (auth.getPrincipal() instanceof UserPrincipal p) return p.getId();
        return auth.getName();
    }
}
