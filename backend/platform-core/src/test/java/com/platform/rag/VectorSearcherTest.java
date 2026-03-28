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
}
