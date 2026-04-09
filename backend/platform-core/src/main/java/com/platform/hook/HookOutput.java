package com.platform.hook;

import java.util.Map;

/**
 * CR-030 PRD-189: 훅 실행 결과.
 *
 * @param decision     결정 (APPROVE/BLOCK/PASSTHROUGH)
 * @param updatedInput 수정된 입력 (null이면 원본 유지)
 * @param metadata     훅이 반환하는 추가 메타데이터
 */
public record HookOutput(
        HookDecision decision,
        Map<String, Object> updatedInput,
        Map<String, Object> metadata
) {
    public static final HookOutput PASSTHROUGH = new HookOutput(HookDecision.PASSTHROUGH, null, Map.of());
    public static final HookOutput APPROVE = new HookOutput(HookDecision.APPROVE, null, Map.of());

    public static HookOutput block(String reason) {
        return new HookOutput(HookDecision.BLOCK, null, Map.of("reason", reason));
    }

    public static HookOutput approve(Map<String, Object> updatedInput) {
        return new HookOutput(HookDecision.APPROVE, updatedInput, Map.of());
    }
}
