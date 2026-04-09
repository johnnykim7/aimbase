package com.platform.context;

import com.platform.llm.model.UnifiedMessage;

import java.util.List;

/**
 * CR-029: 컨텍스트 조립 레이어 단위.
 */
public record AssembledLayer(
        String source,
        List<UnifiedMessage> messages,
        int estimatedTokens,
        int priority,
        boolean evictable
) {}
