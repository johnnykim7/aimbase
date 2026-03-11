package com.platform.tenant;

/**
 * ThreadLocal 기반 테넌트 컨텍스트.
 * Virtual Thread 환경에서도 안전하게 동작 (ThreadLocal은 VT별 독립).
 * TenantResolver에서 설정, 요청 완료 후 반드시 clear() 호출 필요.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    public static void setTenantId(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static boolean hasTenant() {
        return CURRENT_TENANT.get() != null;
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
