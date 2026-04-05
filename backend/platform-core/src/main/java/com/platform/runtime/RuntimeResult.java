package com.platform.runtime;

import com.platform.llm.model.LLMResponse;

/**
 * CR-029: 런타임 실행 결과.
 * selectionReason으로 이 런타임이 선택된 이유를 명시 (비교 가능성).
 */
public record RuntimeResult(
        LLMResponse response,
        String runtimeId,
        String selectionReason,
        long totalDurationMs
) {}
