package com.platform.rag;

import com.platform.domain.EmbeddingEntity;
import com.platform.rag.model.RetrievedChunk;
import com.platform.repository.EmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VectorSearcher 단위 테스트.
 * TC-RAG-PP-016~018: Parent-Child 검색, MCP/Fallback 분기
 */
@ExtendWith(MockitoExtension.class)
class VectorSearcherTest {

    @Mock private EmbeddingRepository embeddingRepository;
    @Mock private EmbeddingService embeddingService;
    @Mock private MCPRagClient mcpRagClient;

    private VectorSearcher vectorSearcher;

    @BeforeEach
    void setUp() {
        vectorSearcher = new VectorSearcher(embeddingRepository, embeddingService, mcpRagClient);
    }

    // ─── searchParentChild via MCP ──────────────────────────────────

    @Test
    void searchParentChild_mcpAvailable_shouldUseMCPTool() {
        when(mcpRagClient.isAvailable()).thenReturn(true);
        when(mcpRagClient.parentChildSearch("질문", "src-1", 5))
                .thenReturn(Map.of(
                        "results", List.of(
                                Map.of("content", "부모 컨텐츠", "score", 0.9, "metadata", Map.of())),
                        "success", true));

        List<RetrievedChunk> results = vectorSearcher.searchParentChild("질문", "src-1", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("부모 컨텐츠");
        assertThat(results.get(0).score()).isEqualTo(0.9);
        verify(mcpRagClient).parentChildSearch("질문", "src-1", 5);
    }

    // ─── searchParentChild fallback ──────────────────────────────────

    @Test
    void searchParentChild_mcpUnavailable_shouldFallbackToJava() {
        when(mcpRagClient.isAvailable()).thenReturn(false);

        float[] queryVector = new float[]{0.1f, 0.2f, 0.3f};
        when(embeddingService.embed("질문")).thenReturn(queryVector);

        EmbeddingEntity entity = new EmbeddingEntity();
        entity.setSourceId("src-1");
        entity.setContent("부모 컨텐츠 Java");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("_score", 0.85);
        entity.setMetadata(meta);

        when(embeddingRepository.findSimilarWithParent("src-1", queryVector, 5))
                .thenReturn(List.of(entity));

        List<RetrievedChunk> results = vectorSearcher.searchParentChild("질문", "src-1", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("부모 컨텐츠 Java");
        assertThat(results.get(0).score()).isEqualTo(0.85);
        verify(embeddingRepository).findSimilarWithParent("src-1", queryVector, 5);
    }

    // ─── search 기본 경로 ────────────────────────────────────────────

    @Test
    void search_mcpUnavailable_shouldUseFallbackVectorSearch() {
        when(mcpRagClient.isAvailable()).thenReturn(false);

        float[] queryVector = new float[]{0.1f, 0.2f, 0.3f};
        when(embeddingService.embed("쿼리")).thenReturn(queryVector);

        EmbeddingEntity entity = new EmbeddingEntity();
        entity.setSourceId("src-1");
        entity.setContent("검색 결과");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("_score", 0.92);
        entity.setMetadata(meta);

        when(embeddingRepository.findSimilar("src-1", queryVector, 5))
                .thenReturn(List.of(entity));

        List<RetrievedChunk> results = vectorSearcher.search("쿼리", "src-1", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).isEqualTo("검색 결과");
    }

    // ─── CR-170: 검색 결과의 문서 ID 와 벡터 유사도 ─────────────────────

    @Test
    void searchViaMcp_carriesDocumentIdAndVectorScoreThroughRerank() {
        // TC-RAG-009
        when(mcpRagClient.isAvailable()).thenReturn(true);
        Map<String, Object> hit = Map.of("content", "이슈 본문", "metadata", Map.of(),
                "document_id", "Issue_1", "vector_score", 0.82, "combined_score", 0.01);
        when(mcpRagClient.searchHybrid("질문", "src-1", 5, 0.7, 0.3, null))
                .thenReturn(Map.of("results", List.of(hit)));
        Map<String, Object> reranked = new LinkedHashMap<>(hit);
        reranked.put("rerank_score", 3.2);
        when(mcpRagClient.rerankResults("질문", List.of(hit), 5))
                .thenReturn(Map.of("results", List.of(reranked)));

        List<RetrievedChunk> results = vectorSearcher.search("질문", "src-1", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).documentId()).isEqualTo("Issue_1");
        assertThat(results.get(0).vectorScore()).isEqualTo(0.82);
        assertThat(results.get(0).score()).isEqualTo(3.2);
    }

    @Test
    void searchFallback_usesEntityDocumentIdAndCosineScore() {
        // TC-RAG-009
        when(mcpRagClient.isAvailable()).thenReturn(false);
        float[] queryVector = new float[]{0.1f, 0.2f};
        when(embeddingService.embed("질문")).thenReturn(queryVector);
        EmbeddingEntity entity = new EmbeddingEntity();
        entity.setSourceId("src-1");
        entity.setDocumentId("Issue_2");
        entity.setContent("본문");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("_score", 0.64);
        entity.setMetadata(meta);
        when(embeddingRepository.findSimilar("src-1", queryVector, 5)).thenReturn(List.of(entity));

        List<RetrievedChunk> results = vectorSearcher.search("질문", "src-1", 5);

        assertThat(results.get(0).documentId()).isEqualTo("Issue_2");
        assertThat(results.get(0).vectorScore()).isEqualTo(0.64);
    }
}
