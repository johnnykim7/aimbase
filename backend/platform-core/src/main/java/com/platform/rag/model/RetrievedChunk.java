package com.platform.rag.model;

import java.util.Map;

/**
 * 벡터 검색 결과 청크.
 * <p>
 * chunkId 는 CR-058 에서 추가 — 위젯 원문 미리보기용
 * {@code GET /api/v1/knowledge-sources/{sid}/chunks/{cid}} 호출 키.
 * 기존 코드 호환을 위해 nullable 하며, 벡터 검색 결과에 실제 embedding id 가 없는 경우(= null) 에도
 * 동작이 깨지지 않는다.
 */
public record RetrievedChunk(
        String chunkId,
        String content,
        double score,
        Map<String, Object> metadata,
        String sourceId
) {
    /** 하위 호환 생성자: chunkId 없이 생성된 기존 호출자용. */
    public RetrievedChunk(String content, double score,
                          Map<String, Object> metadata, String sourceId) {
        this(null, content, score, metadata, sourceId);
    }
}
