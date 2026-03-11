package com.platform.rag.model;

import java.util.Map;

public record Chunk(
        String documentId,
        int chunkIndex,
        String content,
        Map<String, Object> metadata
) {}
