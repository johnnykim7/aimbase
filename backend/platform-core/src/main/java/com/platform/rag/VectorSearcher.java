package com.platform.rag;

import com.platform.domain.EmbeddingEntity;
import com.platform.rag.model.RetrievedChunk;
import com.platform.repository.EmbeddingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * pgvector 코사인 유사도 기반 벡터 검색 컴포넌트.
 *
 * v2.0 (CR-002): MCP RAG Pipeline 사용 가능 시 하이브리드 검색(BM25+벡터+RRF)으로 전환.
 */
@Component
public class VectorSearcher {

    private static final Logger log = LoggerFactory.getLogger(VectorSearcher.class);

    private final EmbeddingRepository embeddingRepository;
    @Nullable
    private final EmbeddingService embeddingService;
    private final MCPRagClient mcpRagClient;

    public VectorSearcher(EmbeddingRepository embeddingRepository,
                          @Nullable EmbeddingService embeddingService,
                          MCPRagClient mcpRagClient) {
        this.embeddingRepository = embeddingRepository;
        this.embeddingService = embeddingService;
        this.mcpRagClient = mcpRagClient;
    }

    /**
     * 쿼리 텍스트와 유사한 청크를 검색.
     *
     * MCP 사용 가능 시: 하이브리드 검색(BM25 + 벡터 + RRF) + cross-encoder 리랭킹
     * Fallback: pgvector 코사인 유사도 검색
     *
     * @param query    검색 쿼리 텍스트
     * @param sourceId 검색 대상 지식 소스 ID
     * @param topK     반환할 최대 청크 수
     * @return 유사도 점수 내림차순 정렬된 RetrievedChunk 목록
     */
    public List<RetrievedChunk> search(String query, String sourceId, int topK) {
        return search(query, sourceId, topK, null);
    }

    /**
     * 쿼리 텍스트와 유사한 청크를 검색 (임베딩 모델 지정).
     *
     * @param query          검색 쿼리 텍스트
     * @param sourceId       검색 대상 지식 소스 ID
     * @param topK           반환할 최대 청크 수
     * @param embeddingModel 쿼리 임베딩에 사용할 모델 (null이면 기본 모델)
     * @return 유사도 점수 내림차순 정렬된 RetrievedChunk 목록
     */
    public List<RetrievedChunk> search(String query, String sourceId, int topK, String embeddingModel) {
        if (mcpRagClient.isAvailable()) {
            return searchViaMCP(query, sourceId, topK, embeddingModel);
        }
        return searchFallback(query, sourceId, topK);
    }

    /**
     * PRD-117: Parent-Child 계층적 검색.
     * child 청크로 정밀 매칭 → 매칭된 parent의 풍부한 컨텍스트 반환.
     *
     * MCP 사용 가능 시: Python parent_child_search 도구 사용
     * Fallback: Java EmbeddingRepository.findSimilarWithParent()
     */
    @SuppressWarnings("unchecked")
    public List<RetrievedChunk> searchParentChild(String query, String sourceId, int topK) {
        if (mcpRagClient.isAvailable()) {
            log.debug("MCP parent-child search for '{}' in sourceId='{}'", query, sourceId);
            Map<String, Object> result = mcpRagClient.parentChildSearch(query, sourceId, topK);
            List<Map<String, Object>> results = result.get("results") instanceof List
                    ? (List<Map<String, Object>>) result.get("results") : List.of();

            return results.stream()
                    .map(r -> new RetrievedChunk(
                            (String) r.getOrDefault("content", ""),
                            r.containsKey("score") ? ((Number) r.get("score")).doubleValue() : 0.0,
                            r.get("metadata") instanceof Map ? (Map<String, Object>) r.get("metadata") : Map.of(),
                            sourceId))
                    .toList();
        }

        // Fallback: Java parent-child 검색 (EmbeddingService 필수)
        if (embeddingService == null) {
            throw new IllegalStateException(
                    "EmbeddingService unavailable. Set spring.ai.openai.api-key or enable RAG MCP sidecar.");
        }
        log.debug("Fallback parent-child search for '{}' in sourceId='{}'", query, sourceId);
        float[] queryVector = embeddingService.embed(query);
        List<EmbeddingEntity> results = embeddingRepository.findSimilarWithParent(sourceId, queryVector, topK);

        return results.stream()
                .map(entity -> {
                    Map<String, Object> meta = entity.getMetadata();
                    double score = meta != null && meta.containsKey("_score")
                            ? ((Number) meta.get("_score")).doubleValue() : 0.0;
                    return new RetrievedChunk(entity.getContent(), score, meta, entity.getSourceId());
                })
                .toList();
    }

    /**
     * Python MCP Server를 통한 하이브리드 검색 + 리랭킹 (CR-002).
     *
     * @param embeddingModel 쿼리 임베딩에 사용할 모델 (null이면 사이드카 기본 모델)
     */
    @SuppressWarnings("unchecked")
    private List<RetrievedChunk> searchViaMCP(String query, String sourceId, int topK, String embeddingModel) {
        log.debug("MCP hybrid search for '{}' in sourceId='{}', topK={}, model='{}'",
                query, sourceId, topK, embeddingModel);

        Map<String, Object> searchResult = mcpRagClient.searchHybrid(
                query, sourceId, topK, 0.7, 0.3, embeddingModel);

        List<Map<String, Object>> results = searchResult.get("results") instanceof List
                ? (List<Map<String, Object>>) searchResult.get("results") : List.of();

        // 리랭킹 적용
        if (!results.isEmpty()) {
            try {
                Map<String, Object> rerankResult = mcpRagClient.rerankResults(query, results, topK);
                List<Map<String, Object>> reranked = rerankResult.get("results") instanceof List
                        ? (List<Map<String, Object>>) rerankResult.get("results") : results;
                results = reranked;
            } catch (Exception e) {
                log.warn("Reranking failed, using hybrid search results: {}", e.getMessage());
            }
        }

        return results.stream()
                .map(r -> {
                    String content = (String) r.getOrDefault("content", "");
                    double score = r.containsKey("rerank_score")
                            ? ((Number) r.get("rerank_score")).doubleValue()
                            : r.containsKey("combined_score")
                                    ? ((Number) r.get("combined_score")).doubleValue()
                                    : 0.0;
                    Map<String, Object> meta = r.get("metadata") instanceof Map
                            ? (Map<String, Object>) r.get("metadata") : Map.of();
                    return new RetrievedChunk(content, score, meta, sourceId);
                })
                .toList();
    }

    /**
     * Fallback: 기존 Java pgvector 코사인 유사도 검색.
     */
    private List<RetrievedChunk> searchFallback(String query, String sourceId, int topK) {
        if (embeddingService == null) {
            throw new IllegalStateException(
                    "EmbeddingService unavailable. Set spring.ai.openai.api-key or enable RAG MCP sidecar.");
        }
        float[] queryVector = embeddingService.embed(query);
        log.debug("Fallback vector search for '{}' in sourceId='{}', topK={}", query, sourceId, topK);

        List<EmbeddingEntity> results = embeddingRepository.findSimilar(sourceId, queryVector, topK);

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
