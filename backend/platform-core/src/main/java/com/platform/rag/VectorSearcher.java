package com.platform.rag;

import com.platform.domain.EmbeddingEntity;
import com.platform.rag.model.RetrievedChunk;
import com.platform.repository.EmbeddingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * pgvector 코사인 유사도 기반 벡터 검색 컴포넌트.
 */
@Component
public class VectorSearcher {

    private static final Logger log = LoggerFactory.getLogger(VectorSearcher.class);

    private final EmbeddingRepository embeddingRepository;
    private final EmbeddingService embeddingService;

    public VectorSearcher(EmbeddingRepository embeddingRepository,
                          EmbeddingService embeddingService) {
        this.embeddingRepository = embeddingRepository;
        this.embeddingService = embeddingService;
    }

    /**
     * 쿼리 텍스트와 유사한 청크를 벡터 검색으로 조회.
     *
     * @param query    검색 쿼리 텍스트
     * @param sourceId 검색 대상 지식 소스 ID (null이면 전체 검색 — 현재 미지원)
     * @param topK     반환할 최대 청크 수
     * @return 유사도 점수 내림차순 정렬된 RetrievedChunk 목록
     */
    public List<RetrievedChunk> search(String query, String sourceId, int topK) {
        // 쿼리 임베딩
        float[] queryVector = embeddingService.embed(query);
        log.debug("Searching for '{}' in sourceId='{}', topK={}", query, sourceId, topK);

        // pgvector 유사도 검색
        List<EmbeddingEntity> results = embeddingRepository.findSimilar(sourceId, queryVector, topK);

        // EmbeddingEntity → RetrievedChunk 변환
        return results.stream()
                .map(entity -> {
                    Map<String, Object> meta = entity.getMetadata();
                    double score = meta != null && meta.containsKey("_score")
                            ? ((Number) meta.get("_score")).doubleValue()
                            : 0.0;
                    return new RetrievedChunk(
                            entity.getContent(),
                            score,
                            meta,
                            entity.getSourceId()
                    );
                })
                .toList();
    }
}
