package com.platform.llm.model;

import java.util.Map;

public record UnifiedToolDef(
        String name,
        String description,
        Map<String, Object> inputSchema
) {}
