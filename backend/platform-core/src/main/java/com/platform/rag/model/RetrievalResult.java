package com.platform.rag.model;

import java.util.List;

public record RetrievalResult(
        List<RetrievedChunk> chunks,
        int totalFound
) {
    public boolean isEmpty() {
        return chunks == null || chunks.isEmpty();
    }
}
