package com.platform.session;

import com.platform.auth.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * CR-178 BIZ-170: 대화와 위젯 프로젝트의 주인. 위젯 토큰은 user_ref, 로그인은 사용자 id 다.
 *
 * <p>user_ref 가 없는 위젯 토큰은 주인이 토큰 subject 나 "widget" 하나로 모여 남의 대화가 섞이므로 주인이 없다고 본다.
 */
public final class ChatOwner {

    public static final String WIDGET_ROLE = "WIDGET";
    /** 위젯 토큰 사용자 정보에 user_ref 를 담는 칸. JwtAuthenticationFilter 가 채운다. */
    public static final String USER_REF_KEY = "user_ref";

    private ChatOwner() {}

    public static UserPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        return auth.getPrincipal() instanceof UserPrincipal up ? up : null;
    }

    public static boolean isWidget(UserPrincipal principal) {
        return principal != null && WIDGET_ROLE.equals(principal.getRole());
    }

    /** 주인 값. user_ref 없는 위젯 토큰이나 사용자 정보가 없는 인증이면 null. */
    public static String of(UserPrincipal principal) {
        if (principal == null) return null;
        if (isWidget(principal)) {
            Object ref = principal.getPermissions().get(USER_REF_KEY);
            return ref instanceof String s && !s.isBlank() ? s : null;
        }
        return principal.getId();
    }

    /** 지금 요청의 주인. */
    public static String current() {
        return of(currentPrincipal());
    }

    /**
     * 세션 주인 칸과 요청자가 같은지. 로그인 세션은 CR-178 전에 이메일로 비교했고 이후 사용자 id 로 적으므로 둘 다 본다.
     */
    public static boolean owns(String sessionOwner, Authentication auth) {
        if (sessionOwner == null || auth == null) return false;
        if (auth.getPrincipal() instanceof UserPrincipal up) {
            if (isWidget(up)) return sessionOwner.equals(of(up));
            return sessionOwner.equals(up.getId()) || sessionOwner.equals(up.getUsername());
        }
        return sessionOwner.equals(auth.getName());
    }
}
