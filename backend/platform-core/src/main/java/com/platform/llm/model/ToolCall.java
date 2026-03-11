package com.platform.llm.model;

import java.util.Map;

public record ToolCall(
        String id,
        String name,
        Map<String, Object> input
) {}
