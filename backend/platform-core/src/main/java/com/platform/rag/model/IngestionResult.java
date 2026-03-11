package com.platform.rag.model;

import java.util.List;
import java.util.Map;

public record IngestionResult(
        String sourceId,
        boolean success,
        int documentsProcessed,
        int chunksCreated,
        List<Map<String, Object>> errors
) {
    public static IngestionResult success(String sourceId, int docs, int chunks) {
        return new IngestionResult(sourceId, true, docs, chunks, List.of());
    }

    public static IngestionResult failure(String sourceId, List<Map<String, Object>> errors) {
        return new IngestionResult(sourceId, false, 0, 0, errors);
    }
}
