package com.platform.session;

/**
 * 압축 임계값 (PRD-203, PRD-206, CR-030 Phase 5).
 *
 * 각 비율은 0.0 ~ 1.0 범위. application.yml 기본값 + 환경변수 override.
 *
 * @param snipRatio          Level 1 SNIP 트리거 (기본 0.70)
 * @param microCompactRatio  Level 2 MICRO_COMPACT 트리거 (기본 0.85)
 * @param autoCompactRatio   Level 3 AUTO_COMPACT 트리거 (기본 0.93)
 * @param sessionMemoryRatio Level 4 SESSION_MEMORY 트리거 (기본 0.91, auto 이전)
 * @param blockingLimitRatio Level 5 BLOCK 한계 (기본 0.98)
 */
public record CompactionThresholds(
        double snipRatio,
        double microCompactRatio,
        double sessionMemoryRatio,
        double autoCompactRatio,
        double blockingLimitRatio
) {

    /** 기본 임계값 */
    public static final CompactionThresholds DEFAULT = new CompactionThresholds(
            0.70, 0.85, 0.91, 0.93, 0.98
    );
}
