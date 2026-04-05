package com.platform.runtime;

import com.platform.llm.model.UnifiedMessage;
import com.platform.tool.ToolContext;

import java.util.List;

/**
 * CR-029: 런타임 실행 요청.
 */
public record RuntimeRequest(
        List<UnifiedMessage> messages,
        ToolContext toolContext,
        String model,
        boolean stream
) {}
