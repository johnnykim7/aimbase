package com.platform.tenant.onboarding;

/**
 * 테넌트 프로비저닝 결과 DTO.
 */
public record TenantOnboardingResult(
    boolean success,
    String tenantId,
    String message,
    String error
) {
    public static TenantOnboardingResult success(String tenantId) {
        return new TenantOnboardingResult(true, tenantId, "Tenant provisioned successfully", null);
    }

    public static TenantOnboardingResult failure(String tenantId, String error) {
        return new TenantOnboardingResult(false, tenantId, null, error);
    }
}
