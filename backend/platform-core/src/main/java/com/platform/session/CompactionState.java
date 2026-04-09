package com.platform.session;

/**
 * 압축 상태 판정 결과 (PRD-203, CR-030 Phase 5).
 *
 * 현재 토큰 사용량을 기반으로 각 임계값 초과 여부를 표현한다.
 *
 * @param usageRatio         현재 사용률 (0.0 ~ 1.0)
 * @param isAboveWarning     경고 임계값 초과 (micro_compact 수준)
 * @param isAboveError       에러 임계값 초과 (auto_compact 수준)
 * @param isAboveAutoCompact auto_compact 트리거 초과
 * @param isAtBlockingLimit  차단 한계 도달
 * @param strategyUsed       실제 적용된 압축 전략 (null = 압축 불필요)
 */
public record CompactionState(
        double usageRatio,
        boolean isAboveWarning,
        boolean isAboveError,
        boolean isAboveAutoCompact,
        boolean isAtBlockingLimit,
        CompactionStrategy strategyUsed
) {

    /** 압축이 불필요한 정상 상태 */
    public static CompactionState normal(double usageRatio) {
        return new CompactionState(usageRatio, false, false, false, false, null);
    }

    /** 임계값과 사용률로 상태 판정 */
    public static CompactionState evaluate(double usageRatio, CompactionThresholds thresholds,
                                           CompactionStrategy strategyUsed) {
        return new CompactionState(
                usageRatio,
                usageRatio >= thresholds.microCompactRatio(),
                usageRatio >= thresholds.autoCompactRatio(),
                usageRatio >= thresholds.autoCompactRatio(),
                usageRatio >= thresholds.blockingLimitRatio(),
                strategyUsed
        );
    }
}
