package com.platform.session;

import com.platform.auth.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * CR-178 BIZ-170: 대화와 위젯 프로젝트의 주인. 위젯 토큰은 user_ref, 로그인은 사용자 id 다.
 *
 * <p>user_ref 가 없는 위젯 토큰은 주인이 토큰 subject 나 "widget" 하나로 모여 남의 대화가 섞이므로 주인이 없다고 본다.
 */
public final class ChatOwner {

    public static final String WIDGET_ROLE = "WIDGET";
    /** 위젯 토큰 사용자 정보에 user_ref 를 담는 칸. JwtAuthenticationFilter 가 채운다. */
    public static final String USER_REF_KEY = "user_ref";
    /** CR-194: 묻는 사람 이름(BIZ-278). */
    public static final String USER_NAME_KEY = "user_name";
    /** CR-194: 함께 보기 키(BIZ-275). */
    public static final String SHARE_KEY_KEY = "share_key";
    /** CR-206: 허용 자료 목록(BIZ-290). 첫 자료가 기본 자료다. */
    public static final String RAG_SOURCE_IDS_KEY = "rag_source_ids";

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

    /** CR-194: 위젯 토큰의 묻는 사람 이름. 위젯 토큰이 아니거나 이름이 없으면 null. */
    public static String userNameOf(UserPrincipal principal) {
        return widgetValue(principal, USER_NAME_KEY);
    }

    /** CR-194: 위젯 토큰의 함께 보기 키. 위젯 토큰이 아니거나 키가 없으면 null. 로그인은 함께 보는 대화를 열지 않는다. */
    public static String shareKeyOf(UserPrincipal principal) {
        return widgetValue(principal, SHARE_KEY_KEY);
    }

    public static String shareKeyOf(Authentication auth) {
        return auth != null && auth.getPrincipal() instanceof UserPrincipal up ? shareKeyOf(up) : null;
    }

    public static String currentUserName() {
        return userNameOf(currentPrincipal());
    }

    public static String currentShareKey() {
        return shareKeyOf(currentPrincipal());
    }

    /**
     * CR-206: 위젯 토큰의 허용 자료 목록. 위젯 토큰이 아니거나 목록이 없으면 빈 목록.
     * 자료 격리는 사람이 아니라 사무소 단위라 user_ref 가 없는 토큰에도 실린다(BIZ-290).
     */
    public static List<String> ragSourceIdsOf(UserPrincipal principal) {
        if (!isWidget(principal)) return List.of();
        Object value = principal.getPermissions().get(RAG_SOURCE_IDS_KEY);
        if (!(value instanceof List<?> list) || list.isEmpty()) return List.of();
        List<String> ids = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) ids.add(s);
        }
        return List.copyOf(ids);
    }

    public static List<String> currentRagSourceIds() {
        return ragSourceIdsOf(currentPrincipal());
    }

    private static String widgetValue(UserPrincipal principal, String key) {
        if (!isWidget(principal)) return null;
        Object value = principal.getPermissions().get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
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
