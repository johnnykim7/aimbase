package com.platform.session;

/**
 * 컨텍스트 윈도우 차단 한계 도달 예외 (PRD-205, CR-030 Phase 5).
 *
 * 사용률이 blockingLimitRatio(기본 98%)를 초과하면 발생.
 * GlobalExceptionHandler에서 HTTP 429로 매핑.
 */
public class BlockingLimitException extends RuntimeException {

    private final double usageRatio;

    public BlockingLimitException(double usageRatio) {
        super(String.format("Context window blocking limit reached (usage: %.1f%%)", usageRatio * 100));
        this.usageRatio = usageRatio;
    }

    public double getUsageRatio() {
        return usageRatio;
    }
}
