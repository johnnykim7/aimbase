package com.platform.tenant.onboarding;

/**
 * 테넌트 생성 요청 DTO.
 */
public record TenantOnboardingRequest(
    String tenantId,
    String appId,       // 소속 App(소비앱) ID — nullable for legacy tenants
    String name,
    String adminEmail,
    String initialAdminPassword,
    String plan,        // free, starter, pro, enterprise
    String dbHost,
    int dbPort,
    String dbUsername,
    String dbPassword
) {
    public static TenantOnboardingRequest withLocalDb(
            String tenantId, String appId, String name, String adminEmail,
            String initialAdminPassword, String plan,
            String dbHost, int dbPort, String dbUsername, String dbPassword) {
        return new TenantOnboardingRequest(
            tenantId, appId, name, adminEmail, initialAdminPassword, plan,
            dbHost, dbPort, dbUsername, dbPassword
        );
    }
}
