package com.platform.tool;

/**
 * CR-029: 도구 입력 검증 결과.
 */
public record ValidationResult(
        boolean valid,
        String message,
        int errorCode
) {
    public static final ValidationResult OK = new ValidationResult(true, null, 0);

    public static ValidationResult fail(String message, int errorCode) {
        return new ValidationResult(false, message, errorCode);
    }

    public static ValidationResult fail(String message) {
        return fail(message, 1);
    }
}
