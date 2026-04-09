package com.platform.tenant;

/**
 * CR-021: 프로젝트 스코핑 컨텍스트.
 * TenantContext와 동일한 ThreadLocal 패턴.
 * X-Project-Id 헤더가 있으면 해당 프로젝트 스코프, 없으면 null (회사 전체).
 */
public class ProjectContext {

    private static final ThreadLocal<String> CURRENT_PROJECT_ID = new ThreadLocal<>();

    public static void setProjectId(String projectId) {
        CURRENT_PROJECT_ID.set(projectId);
    }

    public static String getProjectId() {
        return CURRENT_PROJECT_ID.get();
    }

    public static void clear() {
        CURRENT_PROJECT_ID.remove();
    }
}
