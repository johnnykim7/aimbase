package com.platform.rag;

import com.platform.domain.RetrievalConfigEntity;
import com.platform.rag.model.RetrievedChunk;
import com.platform.repository.RetrievalConfigRepository;
import com.platform.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * RAGService 단위 테스트.
 * TC-RAG-PP-010~015: Query Transform, Parent-Child, Cache, Citation
 */
@ExtendWith(MockitoExtension.class)
class RAGServiceTest {

    @Mock private VectorSearcher vectorSearcher;
    @Mock private RetrievalConfigRepository retrievalConfigRepository;
    @Mock private MCPRagClient mcpRagClient;
    @Mock private PromptTemplateService promptTemplateService;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RAGService(vectorSearcher, retrievalConfigRepository, mcpRagClient, promptTemplateService);
    }

    // ─── buildContext 기본 ────────────────────────────────────────

    @Test
    void buildContext_noConfig_shouldUseDefaultTopK() {
        when(retrievalConfigRepository.findById("src-1")).thenReturn(Optional.empty());
        when(retrievalConfigRepository.findByIsActiveTrue()).thenReturn(List.of());
        when(vectorSearcher.search(eq("질문"), eq("src-1"), eq(5)))
                .thenReturn(List.of(
                        new RetrievedChunk("답변 내용입니다", 0.85, Map.of(), "src-1")));

        String context = ragService.buildContext("질문", "src-1", 0);

        assertThat(context).contains("[1]");
        assertThat(context).contains("답변 내용입니다");
        assertThat(context).contains("0.850");
    }

    @Test
    void buildContext_noResults_shouldReturnEmpty() {
        when(retrievalConfigRepository.findById("src-1")).thenReturn(Optional.empty());
        when(retrievalConfigRepository.findByIsActiveTrue()).thenReturn(List.of());
        when(vectorSearcher.search(any(), eq("src-1"), anyInt()))
                .thenReturn(List.of());

        String context = ragService.buildContext("없는 질문", "src-1", 5);
        assertThat(context).isEmpty();
    }

    // ─── PRD-119: Query Transform ─────────────────────────────────

    @Test
    void buildContext_withQueryTransform_shouldUseTransformedQueries() {
        RetrievalConfigEntity config = new RetrievalConfigEntity();
        config.setId("src-1");
        config.setName("test");
        config.setTopK(3);
        config.setSearchType("hybrid");
        config.setQueryProcessing(Map.of("transform_strategy", "multi_query"));
        config.setActive(true);

        when(retrievalConfigRepository.findById("src-1")).thenReturn(Optional.of(config));
        when(mcpRagClient.isAvailable()).thenReturn(true);
        when(mcpRagClient.transformQuery(eq("원본 질문"), eq("multi_query")))
                .thenReturn(Map.of(
                        "original_query", "원본 질문",
                        "transformed_queries", List.of("원본 질문", "변형 질문1", "변형 질문2")));

        when(vectorSearcher.search(eq("원본 질문"), eq("src-1"), eq(3)))
                .thenReturn(List.of(new RetrievedChunk("결과1", 0.9, Map.of(), "src-1")));
        when(vectorSearcher.search(eq("변형 질문1"), eq("src-1"), eq(3)))
                .thenReturn(List.of(new RetrievedChunk("결과2", 0.8, Map.of(), "src-1")));
        when(vectorSearcher.search(eq("변형 질문2"), eq("src-1"), eq(3)))
                .thenReturn(List.of(new RetrievedChunk("결과3", 0.7, Map.of(), "src-1")));

        String context = ragService.buildContext("원본 질문", "src-1", 0);

        assertThat(context).contains("결과1");
        assertThat(context).contains("결과2");
        assertThat(context).contains("결과3");
        verify(mcpRagClient).transformQuery(eq("원본 질문"), eq("multi_query"));
    }

    @Test
    void buildContext_queryTransformFailed_shouldFallbackToOriginalQuery() {
        RetrievalConfigEntity config = new RetrievalConfigEntity();
        config.setId("src-1");
        config.setName("test");
        config.setTopK(3);
        config.setQueryProcessing(Map.of("transform_strategy", "hyde"));
        config.setActive(true);

        when(retrievalConfigRepository.findById("src-1")).thenReturn(Optional.of(config));
        when(mcpRagClient.isAvailable()).thenReturn(true);
        when(mcpRagClient.transformQuery(any(), any()))
                .thenThrow(new RuntimeException("MCP error"));
        when(vectorSearcher.search(eq("질문"), eq("src-1"), eq(3)))
                .thenReturn(List.of(new RetrievedChunk("결과", 0.9, Map.of(), "src-1")));

        String context = ragService.buildContext("질문", "src-1", 0);
        assertThat(context).contains("결과");
    }

    // ─── PRD-117: Parent-Child Search ─────────────────────────────

    @Test
    void buildContext_parentChildSearchType_shouldUseHierarchicalSearch() {
        RetrievalConfigEntity config = new RetrievalConfigEntity();
        config.setId("src-1");
        config.setName("test");
        config.setTopK(5);
        config.setSearchType("parent_child");
        config.setActive(true);

        when(retrievalConfigRepository.findById("src-1")).thenReturn(Optional.of(config));
        when(vectorSearcher.searchParentChild(eq("질문"), eq("src-1"), eq(5)))
                .thenReturn(List.of(new RetrievedChunk("부모 청크 내용", 0.88, Map.of(), "src-1")));

        String context = ragService.buildContext("질문", "src-1", 0);

        assertThat(context).contains("부모 청크 내용");
        verify(vectorSearcher).searchParentChild(any(), any(), anyInt());
        verify(vectorSearcher, never()).search(any(), any(), anyInt());
    }

    // ─── PRD-124: Semantic Cache ──────────────────────────────────

    @Test
    void buildContextWithCache_cacheHit_shouldReturnCachedResponse() {
        when(mcpRagClient.isAvailable()).thenReturn(true);
        when(mcpRagClient.callToolRaw(eq("cache_lookup"), any()))
                .thenReturn(Map.of(
                        "hit", true,
                        "response_text", "캐시된 컨텍스트",
                        "similarity", 0.97));

        String context = ragService.buildContextWithCache("질문", "src-1", 5);

        assertThat(context).isEqualTo("캐시된 컨텍스트");
        verify(vectorSearcher, never()).search(any(), any(), anyInt());
    }

    @Test
    void buildContextWithCache_cacheMiss_shouldSearchAndStore() {
        // 캐시 미스
        when(mcpRagClient.isAvailable()).thenReturn(true);
        when(mcpRagClient.callToolRaw(eq("cache_lookup"), any()))
                .thenReturn(Map.of("hit", false));
        when(mcpRagClient.callToolRaw(eq("cache_store"), any()))
                .thenReturn(Map.of("success", true));

        // 검색 결과
        when(retrievalConfigRepository.findById("src-1")).thenReturn(Optional.empty());
        when(retrievalConfigRepository.findByIsActiveTrue()).thenReturn(List.of());
        when(vectorSearcher.search(any(), eq("src-1"), anyInt()))
                .thenReturn(List.of(new RetrievedChunk("검색 결과", 0.8, Map.of(), "src-1")));

        String context = ragService.buildContextWithCache("질문", "src-1", 5);

        assertThat(context).contains("검색 결과");
        verify(mcpRagClient).callToolRaw(eq("cache_store"), any());
    }

    // ─── PRD-125: Citation ────────────────────────────────────────

    @Test
    void buildContextWithCitations_shouldReturnCitationsMetadata() {
        when(retrievalConfigRepository.findById("src-1")).thenReturn(Optional.empty());
        when(retrievalConfigRepository.findByIsActiveTrue()).thenReturn(List.of());
        when(vectorSearcher.search(any(), eq("src-1"), anyInt()))
                .thenReturn(List.of(
                        new RetrievedChunk("첫 번째 내용", 0.9, Map.of(), "src-1"),
                        new RetrievedChunk("두 번째 내용", 0.8, Map.of(), "src-1")));

        Map<String, Object> result = ragService.buildContextWithCitations("질문", "src-1", 5);

        assertThat(result).containsKey("context");
        assertThat(result).containsKey("citations");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> citations = (List<Map<String, Object>>) result.get("citations");
        assertThat(citations).hasSizeGreaterThanOrEqualTo(2);
        assertThat(citations.get(0)).containsEntry("index", 1);
        assertThat(citations.get(0)).containsEntry("source_id", "src-1");
    }
}
