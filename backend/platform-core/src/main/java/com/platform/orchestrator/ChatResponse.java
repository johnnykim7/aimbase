package com.platform.orchestrator;

import com.platform.llm.model.ContentBlock;
import com.platform.llm.model.TokenUsage;

import java.util.List;
import java.util.Map;

public record ChatResponse(
        String id,
        String model,
        String sessionId,
        List<ContentBlock> content,
        List<Map<String, Object>> actionsExecuted,
        TokenUsage usage,
        double costUsd
) {}
