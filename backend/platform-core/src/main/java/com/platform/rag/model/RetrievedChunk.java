package com.platform.rag.model;

import java.util.Map;

public record RetrievedChunk(
        String content,
        double score,
        Map<String, Object> metadata,
        String sourceId
) {}
