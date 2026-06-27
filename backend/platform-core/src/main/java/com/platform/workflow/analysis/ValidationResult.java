package com.platform.workflow.analysis;

/**
 * CR-120: 청크 결과 동작별 검증 결과.
 *
 * <p>{@code valid=false} 면 엔진이 그 청크를 재시도(item_retry_max)하고, 끝내 실패하면
 * 청크 FAILED 로 정직하게 표시한다(CR-119 철학 — 빈응답/규격위반을 가짜 성공으로 둔갑시키지 않음).
 */
public record ValidationResult(boolean valid, String message) {

    private static final ValidationResult OK = new ValidationResult(true, null);

    public static ValidationResult ok() { return OK; }

    public static ValidationResult fail(String message) {
        return new ValidationResult(false, message);
    }
}
