package com.platform.app;

/**
 * ThreadLocal 기반 App(소비앱) 컨텍스트.
 * TenantResolver에서 설정, 요청 완료 후 반드시 clear() 호출 필요.
 */
public final class AppContext {

    private static final ThreadLocal<String> CURRENT_APP = new ThreadLocal<>();

    private AppContext() {}

    public static void setAppId(String appId) {
        CURRENT_APP.set(appId);
    }

    public static String getAppId() {
        return CURRENT_APP.get();
    }

    public static boolean hasApp() {
        return CURRENT_APP.get() != null;
    }

    public static void clear() {
        CURRENT_APP.remove();
    }
}
