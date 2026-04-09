package com.platform.hook;

/**
 * CR-030 PRD-189: 훅 실행 결과 결정.
 *
 * 복수 훅 실행 시 집계 규칙: BLOCK > APPROVE > PASSTHROUGH.
 * 하나라도 BLOCK이면 최종 BLOCK.
 */
public enum HookDecision {

    /** 훅이 해당 동작을 명시적으로 승인 */
    APPROVE,

    /** 훅이 해당 동작을 차단 (첫 BLOCK에서 최종 결정) */
    BLOCK,

    /** 훅이 결정을 위임 (다음 훅으로 전달, 기본값) */
    PASSTHROUGH
}
