package com.platform.app.onboarding;

public record AppOnboardingResult(
    String appId,
    String dbName,
    boolean success,
    String message
) {
    public static AppOnboardingResult success(String appId, String dbName) {
        return new AppOnboardingResult(appId, dbName, true, "App provisioned successfully");
    }

    public static AppOnboardingResult failure(String appId, String message) {
        return new AppOnboardingResult(appId, null, false, message);
    }
}
