package com.platform.tenant.onboarding;

/**
 * 테넌트 생성 요청 DTO.
 */
public record TenantOnboardingRequest(
    String tenantId,
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
            String tenantId, String name, String adminEmail,
            String initialAdminPassword, String plan,
            String dbHost, int dbPort, String dbUsername, String dbPassword) {
        return new TenantOnboardingRequest(
            tenantId, name, adminEmail, initialAdminPassword, plan,
            dbHost, dbPort, dbUsername, dbPassword
        );
    }
}
