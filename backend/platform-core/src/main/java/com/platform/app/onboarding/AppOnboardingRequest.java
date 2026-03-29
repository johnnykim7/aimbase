package com.platform.app.onboarding;

/**
 * App(소비앱) 생성 요청 DTO.
 */
public record AppOnboardingRequest(
    String appId,
    String name,
    String description,
    String ownerEmail,
    String ownerPassword,
    Integer maxTenants,
    String dbHost,
    int dbPort,
    String dbUsername,
    String dbPassword
) {
    public static AppOnboardingRequest withLocalDb(
            String appId, String name, String description,
            String ownerEmail, String ownerPassword, Integer maxTenants,
            String dbHost, int dbPort, String dbUsername, String dbPassword) {
        return new AppOnboardingRequest(
            appId, name, description, ownerEmail, ownerPassword,
            maxTenants != null ? maxTenants : 100,
            dbHost, dbPort, dbUsername, dbPassword
        );
    }
}
