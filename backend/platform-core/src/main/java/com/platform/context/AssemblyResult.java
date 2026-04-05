package com.platform.context;

import com.platform.llm.model.UnifiedMessage;

import java.util.List;

/**
 * CR-029: 컨텍스트 조립 결과.
 * 최종 메시지와 조립 과정 추적(trace)을 함께 반환하여 ClaudeTool과 비교 가능하게 한다.
 */
public record AssemblyResult(
        List<UnifiedMessage> messages,
        AssemblyTrace trace
) {}
